package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.config.SubtaskState
import com.anomaly.koncerto.core.config.SubtaskStatus

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