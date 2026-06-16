import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import java.time.Instant

package com.flexsentlabs.koncerto.orchestrator

// SubtaskDef
@kotlinx.serialization.Serializable
data class SubtaskDef(
    val id: String,
    val description: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
    val fileScope: List<String> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        return id == (other as SubtaskDef).id
    }

    override fun hashCode(): Int = id.hashCode()
}

// SubtaskStatus
enum class SubtaskStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED
}

// SubtaskState
data class SubtaskState(
    val def: SubtaskDef,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val branchName: String? = null,
    val runId: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        return def == (other as SubtaskState).def
    }

    override fun hashCode(): Int = def.hashCode()
}

// SubtaskFrontier (implementation)
class SubtaskFrontier {

    fun compute(states: List<SubtaskState>): List<SubtaskState> {
        val completed = states
            .filter { it.status == SubtaskStatus.SUCCEEDED }
            .map { it.def.id }
            .toSet()
        return states
            .filter { it.status == SubtaskStatus.PENDING }
            .filter { it.def.dependsOn.all { dep -> dep in completed } }
    }

    fun topologicalSort(states: List<SubtaskState>): List<SubtaskState> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        val stateMap = states.associateBy { it.def.id }

        for (state in states) {
            adjacency.putIfAbsent(state.def.id, mutableListOf())
            inDegree.putIfAbsent(state.def.id, 0)
        }
        for (state in states) {
            for (dep in state.def.dependsOn) {
                adjacency[dep]?.add(state.def.id)
                inDegree[state.def.id] = (inDegree[state.def.id] ?: 1) + 1
            }
        }

        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        val result = mutableListOf<SubtaskState>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            stateMap[id]?.let { result.add(it) }
            for (neighbor in adjacency[id].orEmpty()) {
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }

        return result
    }
}

fun main() {
    println("Running SubtaskFrontier tests...")
    
    val frontier = SubtaskFrontier()
    
    fun def(id: String, dependsOn: List<String> = emptyList()): SubtaskDef = 
        SubtaskDef(id = id, description = id, prompt = "prompt-$id", dependsOn = dependsOn)
    
    fun pending(def: SubtaskDef): SubtaskState = 
        SubtaskState(def = def, status = SubtaskStatus.PENDING)
    
    fun done(def: SubtaskDef): SubtaskState = 
        SubtaskState(def = def, status = SubtaskStatus.SUCCEEDED)
    
    // Test 1: no deps all frontier
    val s1 = pending(def("a"))
    val s2 = pending(def("b"))
    val result1 = frontier.compute(listOf(s1, s2))
    println("Test 1: ${result1.map { it.def.id }}")
    assertThat(result1.map { it.def.id }).containsExactly("a", "b")
    
    // Test 2: linear chain only first is frontier
    val s3 = pending(def("a"))
    val s4 = pending(def("b", listOf("a")))
    val s5 = pending(def("c", listOf("b")))
    val result2 = frontier.compute(listOf(s3, s4, s5))
    println("Test 2: ${result2.map { it.def.id }}")
    assertThat(result2.map { it.def.id }).containsExactly("a")
    
    // Test 3: completed dep unblocks next
    val s6 = done(def("a"))
    val s7 = pending(def("b", listOf("a")))
    val result3 = frontier.compute(listOf(s6, s7))
    println("Test 3: ${result3.map { it.def.id }}")
    assertThat(result3.map { it.def.id }).containsExactly("b")
    
    // Test 4: diamond dependency
    val s8 = done(def("root"))
    val s9 = pending(def("left", listOf("root")))
    val s10 = pending(def("right", listOf("root")))
    val s11 = pending(def("merge", listOf("left", "right")))
    val result4 = frontier.compute(listOf(s8, s9, s10, s11))
    println("Test 4: ${result4.map { it.def.id }}")
    assertThat(result4.map { it.def.id }).containsExactly("left", "right")
    
    // Test 5: all deps not met returns nothing
    val s12 = pending(def("a", listOf("nonexistent")))
    val result5 = frontier.compute(listOf(s12))
    println("Test 5: ${result5.map { it.def.id }}")
    assertThat(result5).isEmpty()
    
    // Test 6: topological sort linear chain
    val s13 = pending(def("a"))
    val s14 = pending(def("b", listOf("a")))
    val s15 = pending(def("c", listOf("b")))
    val result6 = frontier.topologicalSort(listOf(s15, s14, s13))
    println("Test 6: ${result6.map { it.def.id }}")
    assertThat(result6.map { it.def.id }).containsExactly("a", "b", "c")
    
    // Test 7: topological sort diamond
    val root = pending(def("root"))
    val left = pending(def("left", listOf("root")))
    val right = pending(def("right", listOf("root")))
    val merge = pending(def("merge", listOf("left", "right")))
    val result7 = frontier.topologicalSort(listOf(merge, right, left, root))
    println("Test 7: ${result7.map { it.def.id }}")
    assertThat(result7.map { it.def.id }).containsExactly("root", "left", "right", "merge")
    
    println("\nAll tests passed!")
}
