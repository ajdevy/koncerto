package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isEmpty
import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.model.Issue
import org.junit.jupiter.api.Test

class DependencyGraphTest {

    private fun issue(
        id: String, identifier: String = id, state: String = "Todo",
        blockedBy: List<BlockerRef> = emptyList(), priority: Int? = 1
    ) = Issue(
        id = id, identifier = identifier, title = identifier,
        description = null, priority = priority, state = state,
        branchName = null, url = null, labels = emptyList(),
        blockedBy = blockedBy, creator = null, createdAt = null, updatedAt = null
    )

    @Test
    fun `frontier contains issues with no blockers`() {
        val a = issue("a", "ENG-1")
        val b = issue("b", "ENG-2")
        val graph = DependencyGraph.build(listOf(a, b))
        assertThat(graph.frontier.map { it.id }.toSet()).isEqualTo(setOf("a", "b"))
    }

    @Test
    fun `chain dependency only first issue on frontier`() {
        val c = issue("c", "ENG-3", blockedBy = emptyList())
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("c", "ENG-3", "In Progress")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c))
        assertThat(graph.frontier.map { it.id }).isEqualTo(listOf("c"))
    }

    @Test
    fun `blocker absent from candidates is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("external", "EXT-1", "Done")))
        val graph = DependencyGraph.build(listOf(a))
        assertThat(graph.frontier.map { it.id }).isEqualTo(listOf("a"))
    }

    @Test
    fun `blocker with null id is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef(null, null, null)))
        val graph = DependencyGraph.build(listOf(a))
        assertThat(graph.frontier.map { it.id }).isEqualTo(listOf("a"))
    }

    @Test
    fun `blocker with terminal state is treated as resolved`() {
        val b = issue("b", "ENG-2", state = "Done")
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Done")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertThat(graph.frontier.map { it.id }).isEqualTo(listOf("a"))
    }

    @Test
    fun `diamond dependency`() {
        val d = issue("d", "ENG-4")
        val c = issue("c", "ENG-3")
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c, d))
        assertThat(graph.frontier.map { it.id }.toSet()).isEqualTo(setOf("c", "d"))
    }

    @Test
    fun `all blocked returns empty frontier`() {
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("a", "ENG-1", "In Progress")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "In Progress")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertThat(graph.frontier).isEmpty()
    }

    @Test
    fun `frontier sorted by priority then identifier`() {
        val high = issue("h", "ENG-2", priority = 1)
        val low = issue("l", "ENG-1", priority = 5)
        val graph = DependencyGraph.build(listOf(low, high))
        assertThat(graph.frontier.map { it.id }).isEqualTo(listOf("h", "l"))
    }
}
