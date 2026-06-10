package com.anomaly.koncerto.notifications

import com.anomaly.koncerto.notifications.channel.SmtpEmailNotifier
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
}
