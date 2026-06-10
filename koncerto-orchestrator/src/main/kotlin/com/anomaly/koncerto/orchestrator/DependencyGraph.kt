package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue

data class DependencyGraph(
    val nodes: Map<String, Issue>,
    val edges: Map<String, Set<String>>,
    val frontier: List<Issue>
) {
    companion object {
        fun build(
            candidates: List<Issue>,
            terminalStates: List<String> = listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
        ): DependencyGraph {
            val nodes = candidates.associateBy { it.id }
            val edges = candidates.associate { issue ->
                val blockers = issue.blockedBy
                    .filter { ref -> ref.id != null }
                    .filter { ref -> nodes.containsKey(ref.id) }
                    .filter { ref ->
                        val blockerIssue = nodes[ref.id]!!
                        terminalStates.none { it.equals(blockerIssue.state, ignoreCase = true) }
                    }
                    .mapNotNull { it.id }
                    .toSet()
                issue.id to blockers
            }
            val frontier = candidates
                .filter { issue ->
                    val issueBlockers = edges[issue.id] ?: emptySet()
                    issueBlockers.isEmpty()
                }
                .sortedWith(
                    compareBy<Issue>({ it.priority ?: Int.MAX_VALUE })
                        .thenBy { it.identifier }
                )
            return DependencyGraph(nodes, edges, frontier)
        }
    }
}
