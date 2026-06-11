package com.anomaly.koncerto.core.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object EventBus {
    private val _events = MutableSharedFlow<AgentLifecycleEvent>(replay = 1, extraBufferCapacity = 100)
    val events = _events.asSharedFlow()

    suspend fun publish(event: AgentLifecycleEvent) {
        _events.emit(event)
    }
}
