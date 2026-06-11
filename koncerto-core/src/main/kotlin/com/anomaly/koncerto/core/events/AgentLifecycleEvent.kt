package com.anomaly.koncerto.core.events

sealed class AgentLifecycleEvent {
    abstract val agentKey: String
    abstract val timestamp: Long

    data class Started(
        override val agentKey: String,
        val processId: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentLifecycleEvent()

    data class Completed(
        override val agentKey: String,
        val success: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentLifecycleEvent()

    data class Failed(
        override val agentKey: String,
        val error: String,
        val attempt: Int = 0,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentLifecycleEvent()

    data class Recovered(
        override val agentKey: String,
        val afterError: String,
        val restartCount: Int,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentLifecycleEvent()

    data class Stalled(
        override val agentKey: String,
        val stalledDurationMs: Long,
        override val timestamp: Long = System.currentTimeMillis()
    ) : AgentLifecycleEvent()
}
