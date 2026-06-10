package com.anomaly.koncerto.notifications

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.*

private class FakeNotifier : Notifier {
    var sentEvents = mutableListOf<NotificationEvent>()
    var throwOnSend: Boolean = false

    override suspend fun send(event: NotificationEvent) {
        if (throwOnSend) throw RuntimeException("Fake failure")
        sentEvents.add(event)
    }
}

class NotifierTest {

    @Test
    fun `composite notifier calls all child notifiers`() = runTest {
        val child1 = FakeNotifier()
        val child2 = FakeNotifier()
        val composite = CompositeNotifier(listOf(child1, child2))
        val event = NotificationEvent.AgentCompleted("p", "1", "P-1", "t", null)

        composite.send(event)

        assertThat(child1.sentEvents).hasSize(1)
        assertThat(child1.sentEvents[0]).isSameInstanceAs(event)
        assertThat(child2.sentEvents).hasSize(1)
        assertThat(child2.sentEvents[0]).isSameInstanceAs(event)
    }

    @Test
    fun `composite notifier continues if one notifier throws`() = runTest {
        val throwing = FakeNotifier().also { it.throwOnSend = true }
        val ok = FakeNotifier()
        val composite = CompositeNotifier(listOf(throwing, ok))
        val event = NotificationEvent.AgentFailed("p", "1", "P-1", "t", "err")

        composite.send(event)

        assertThat(throwing.sentEvents).hasSize(0)
        assertThat(ok.sentEvents).hasSize(1)
        assertThat(ok.sentEvents[0]).isSameInstanceAs(event)
    }

    @Test
    fun `composite notifier with empty list does not throw`() = runTest {
        val composite = CompositeNotifier(emptyList())
        val event = NotificationEvent.AgentStalled("p", "1", "P-1", "t", 5000L)
        composite.send(event)
    }
}
