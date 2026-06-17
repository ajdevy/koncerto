package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.channel.LoggingNotifier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LoggingNotifierTest {

    @Test
    fun `send completes without error`() = runTest {
        val logger = StructuredLogger(emptyList())
        val notifier = LoggingNotifier(logger)
        val event = NotificationEvent.AgentCompleted("p", "1", "P-1", "title", null)
        notifier.send(event)
    }

    @Test
    fun `send handles all event types without error`() = runTest {
        val logger = StructuredLogger(emptyList())
        val notifier = LoggingNotifier(logger)

        notifier.send(NotificationEvent.AgentCompleted("p", "1", "P-1", "t", null))
        notifier.send(NotificationEvent.AgentFailed("p", "2", "P-2", "t", "err"))
        notifier.send(NotificationEvent.AgentStalled("p", "3", "P-3", "t", 1000L))
        notifier.send(NotificationEvent.ClarificationRequested("p", "4", "P-4", "t"))
    }

    @Test
    fun `send handles limit detected event without error`() = runTest {
        val logger = StructuredLogger(emptyList())
        val notifier = LoggingNotifier(logger)
        notifier.send(NotificationEvent.LimitDetected("p", "5", "P-5", "t", "rate", "summary", null))
    }
}
