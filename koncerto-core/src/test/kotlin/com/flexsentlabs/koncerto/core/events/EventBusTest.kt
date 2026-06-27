package com.flexsentlabs.koncerto.core.events

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
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

    @Test
    fun `replay delivers last published event to new subscriber`() = runBlocking {
        val key = "replay-${System.nanoTime()}"
        EventBus.publish(AgentLifecycleEvent.Started(key, 1L, timestamp = 100L))
        EventBus.publish(AgentLifecycleEvent.Completed(key, success = true, timestamp = 200L))

        val replayed = EventBus.events.first()
        assertThat(replayed.agentKey).isEqualTo(key)
        assertThat(replayed).isInstanceOf(AgentLifecycleEvent.Completed::class)
        assertThat((replayed as AgentLifecycleEvent.Completed).success).isEqualTo(true)
        assertThat(replayed.timestamp).isEqualTo(200L)
    }

    @Test
    fun `publish all event variants`() = runBlocking {
        val key = "variants-${System.nanoTime()}"
        val events = listOf(
            AgentLifecycleEvent.Started(key, 1L, timestamp = 1L),
            AgentLifecycleEvent.Completed(key, success = false, timestamp = 2L),
            AgentLifecycleEvent.Failed(key, error = "err", attempt = 1, timestamp = 3L),
            AgentLifecycleEvent.Recovered(key, afterError = "err", restartCount = 1, timestamp = 4L),
            AgentLifecycleEvent.Stalled(key, stalledDurationMs = 5000L, timestamp = 5L)
        )
        events.forEach { EventBus.publish(it) }

        val last = EventBus.events.first()
        assertThat(last).isInstanceOf(AgentLifecycleEvent.Stalled::class)
        assertThat((last as AgentLifecycleEvent.Stalled).stalledDurationMs).isEqualTo(5000L)
    }
}
