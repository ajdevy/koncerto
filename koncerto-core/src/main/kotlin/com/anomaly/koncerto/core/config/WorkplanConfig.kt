package com.anomaly.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class WorkplanConfig(
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val maxParallelSubagents: Int = 3
) {
    @Serializable
    enum class ExecutionMode {
        SEQUENTIAL, PARALLEL
    }
}
