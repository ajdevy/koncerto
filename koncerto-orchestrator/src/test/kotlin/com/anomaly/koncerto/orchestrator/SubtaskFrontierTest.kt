package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.core.config.SubtaskDef
import com.anomaly.koncerto.core.config.SubtaskState
import com.anomaly.koncerto.core.config.SubtaskStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubtaskFrontierTest {

    private val frontier = SubtaskFrontier()

    private fun def(id: String, dependsOn: List<String> = emptyList()) =
        SubtaskDef(id = id, description = id, prompt = "prompt-$id", dependsOn = dependsOn)

    private fun pending(def: SubtaskDef) = SubtaskState(def = def, status = SubtaskStatus.PENDING)
    private fun done(def: SubtaskDef) = SubtaskState(def = def, status = SubtaskStatus.SUCCEEDED)

    @Test
    fun `no deps all frontier`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b"))
        assertThat(frontier.compute(listOf(s1, s2)).map { it.def.id })
            .containsExactly("a", "b")
    }

    @Test
    fun `linear chain only first is frontier`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b", listOf("a")))
        val s3 = pending(def("c", listOf("b")))
        assertThat(frontier.compute(listOf(s1, s2, s3)).map { it.def.id })
            .containsExactly("a")
    }

    @Test
    fun `completed dep unblocks next`() {
        val s1 = done(def("a"))
        val s2 = pending(def("b", listOf("a")))
        assertThat(frontier.compute(listOf(s1, s2)).map { it.def.id })
            .containsExactly("b")
    }

    @Test
    fun `diamond dependency`() {
        val s1 = done(def("root"))
        val s2 = pending(def("left", listOf("root")))
        val s3 = pending(def("right", listOf("root")))
        val s4 = pending(def("merge", listOf("left", "right")))
        assertThat(frontier.compute(listOf(s1, s2, s3, s4)).map { it.def.id })
            .containsExactly("left", "right")
    }

    @Test
    fun `all deps not met returns nothing`() {
        val s1 = pending(def("a", listOf("nonexistent")))
        assertThat(frontier.compute(listOf(s1))).isEmpty()
    }

    @Test
    fun `topological sort linear chain`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b", listOf("a")))
        val s3 = pending(def("c", listOf("b")))
        val sorted = frontier.topologicalSort(listOf(s3, s2, s1))
        assertThat(sorted.map { it.def.id }).containsExactly("a", "b", "c")
    }

    @Test
    fun `topological sort diamond`() {
        val root = pending(def("root"))
        val left = pending(def("left", listOf("root")))
        val right = pending(def("right", listOf("root")))
        val merge = pending(def("merge", listOf("left", "right")))
        val sorted = frontier.topologicalSort(listOf(merge, right, left, root))
        assertThat(sorted.map { it.def.id }).containsExactly("root", "left", "right", "merge")
    }
}