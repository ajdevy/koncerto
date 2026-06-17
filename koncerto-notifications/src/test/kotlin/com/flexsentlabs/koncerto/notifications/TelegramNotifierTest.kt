package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.notifications.channel.TelegramNotifier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TelegramNotifierTest {

    @Test
    fun `constructs with valid bot token and chat id`() {
        TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
    }

    @Test
    fun `send formats agent completed event`() = runTest {
        val notifier = TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
        val event = NotificationEvent.AgentCompleted("p", "1", "P-1", "Completed task", null)
        notifier.send(event)
    }

    @Test
    fun `send formats agent failed event`() = runTest {
        val notifier = TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
        val event = NotificationEvent.AgentFailed("p", "2", "P-2", "Failed task", "Error details")
        notifier.send(event)
    }

    @Test
    fun `send formats agent stalled event`() = runTest {
        val notifier = TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
        val event = NotificationEvent.AgentStalled("p", "3", "P-3", "Stalled task", 60_000L)
        notifier.send(event)
    }

    @Test
    fun `send formats clarification requested event`() = runTest {
        val notifier = TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
        val event = NotificationEvent.ClarificationRequested("p", "4", "P-4", "Clarification needed")
        notifier.send(event)
    }

    @Test
    fun `send formats limit detected event`() = runTest {
        val notifier = TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
        val event = NotificationEvent.LimitDetected("p", "5", "P-5", "Rate limit", "rate_limit", "Too many requests", 30_000L)
        notifier.send(event)
    }
}
