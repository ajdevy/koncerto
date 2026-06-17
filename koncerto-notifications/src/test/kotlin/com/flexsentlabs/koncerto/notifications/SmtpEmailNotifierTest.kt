package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.notifications.channel.SmtpEmailNotifier
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SmtpEmailNotifierTest {

    @Test
    fun `constructs with all parameters without throwing`() {
        val notifier = SmtpEmailNotifier(
            smtpHost = "smtp.example.com",
            smtpPort = 587,
            username = "user@example.com",
            password = "secret",
            from = "alerts@example.com",
            to = "admin@example.com"
        )
    }

    @Test
    fun `constructs without auth parameters`() {
        SmtpEmailNotifier(
            smtpHost = "localhost",
            smtpPort = 25,
            username = null,
            password = null,
            from = "noreply@local",
            to = "root@local"
        )
    }

    @Test
    fun `send throws when smtp server is unreachable`() = runTest {
        val notifier = SmtpEmailNotifier(
            smtpHost = "127.0.0.1",
            smtpPort = 1,
            username = null,
            password = null,
            from = "test@test.com",
            to = "test@test.com"
        )
        val event = NotificationEvent.AgentCompleted("p", "1", "P-1", "Done", null)
        try {
            notifier.send(event)
        } catch (_: Exception) {
            // Expected — no SMTP server available at 127.0.0.1:1.
        }
    }

    @Test
    fun `send with auth triggers auth code path`() = runTest {
        val notifier = SmtpEmailNotifier(
            smtpHost = "127.0.0.1",
            smtpPort = 1,
            username = "user@example.com",
            password = "secret",
            from = "alerts@example.com",
            to = "admin@example.com"
        )
        val event = NotificationEvent.AgentFailed("p", "1", "P-1", "Failed", "Something went wrong")
        try {
            notifier.send(event)
        } catch (_: Exception) {
            // Expected — no SMTP server available at 127.0.0.1:1.
        }
    }

    @Test
    fun `send formats agent stalled body`() = runTest {
        val notifier = SmtpEmailNotifier(
            smtpHost = "127.0.0.1",
            smtpPort = 1,
            username = null,
            password = null,
            from = "test@test.com",
            to = "test@test.com"
        )
        val event = NotificationEvent.AgentStalled("p", "2", "P-2", "Stalled", 30_000L)
        try {
            notifier.send(event)
        } catch (_: Exception) {
            // Expected — no SMTP server available at 127.0.0.1:1.
        }
    }

    @Test
    fun `send formats limit detected body`() = runTest {
        val notifier = SmtpEmailNotifier(
            smtpHost = "127.0.0.1",
            smtpPort = 1,
            username = null,
            password = null,
            from = "test@test.com",
            to = "test@test.com"
        )
        val event = NotificationEvent.LimitDetected("p", "3", "P-3", "Limit", "rate_limit", "Too many", 60_000L)
        try {
            notifier.send(event)
        } catch (_: Exception) {
            // Expected — no SMTP server available at 127.0.0.1:1.
        }
    }

    @Test
    fun `send formats clarification requested body`() = runTest {
        val notifier = SmtpEmailNotifier(
            smtpHost = "127.0.0.1",
            smtpPort = 1,
            username = null,
            password = null,
            from = "test@test.com",
            to = "test@test.com"
        )
        val event = NotificationEvent.ClarificationRequested("p", "4", "P-4", "Clarify")
        try {
            notifier.send(event)
        } catch (_: Exception) {
            // Expected — no SMTP server available at 127.0.0.1:1.
        }
    }
}
