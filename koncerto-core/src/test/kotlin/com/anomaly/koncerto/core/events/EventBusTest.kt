package com.anomaly.koncerto.core.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class EventBusTest {

    @Test
    fun `publish and collect events`() = runBlocking {
        val event = AgentLifecycleEvent.Started("agent-X", 12345L)
        EventBus.publish(event)
        val collected = EventBus.events.first()
        assertThat(collected.agentKey).isEqualTo("agent-X")
        assertThat((collected as AgentLifecycleEvent.Started).processId).isEqualTo(12345L)
    }
}
