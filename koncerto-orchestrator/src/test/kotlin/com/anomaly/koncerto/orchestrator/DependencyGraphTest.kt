package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        assertEquals(setOf("a", "b"), graph.frontier.map { it.id }.toSet())
    }

    @Test
    fun `chain dependency only first issue on frontier`() {
        val c = issue("c", "ENG-3", blockedBy = emptyList())
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("c", "ENG-3", "In Progress")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c))
        assertEquals(listOf("c"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker absent from candidates is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("external", "EXT-1", "Done")))
        val graph = DependencyGraph.build(listOf(a))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker with null id is treated as resolved`() {
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef(null, null, null)))
        val graph = DependencyGraph.build(listOf(a))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `blocker with terminal state is treated as resolved`() {
        val b = issue("b", "ENG-2", state = "Done")
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "Done")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertEquals(listOf("a"), graph.frontier.map { it.id })
    }

    @Test
    fun `diamond dependency`() {
        val d = issue("d", "ENG-4")
        val c = issue("c", "ENG-3")
        val b = issue("b", "ENG-2", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("d", "ENG-4", "Todo")))
        val graph = DependencyGraph.build(listOf(a, b, c, d))
        assertEquals(setOf("c", "d"), graph.frontier.map { it.id }.toSet())
    }

    @Test
    fun `all blocked returns empty frontier`() {
        val b = issue("b", "ENG-2")
        val a = issue("a", "ENG-1", blockedBy = listOf(BlockerRef("b", "ENG-2", "In Progress")))
        val graph = DependencyGraph.build(listOf(a, b))
        assertTrue(graph.frontier.isEmpty())
    }

    @Test
    fun `frontier sorted by priority then identifier`() {
        val high = issue("h", "ENG-2", priority = 1)
        val low = issue("l", "ENG-1", priority = 5)
        val graph = DependencyGraph.build(listOf(low, high))
        assertEquals(listOf("h", "l"), graph.frontier.map { it.id })
    }
}
