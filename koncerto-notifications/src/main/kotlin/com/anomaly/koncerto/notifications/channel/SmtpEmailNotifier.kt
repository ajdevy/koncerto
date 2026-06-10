package com.anomaly.koncerto.notifications.channel

import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.notifications.Notifier
import java.util.Properties
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage

class SmtpEmailNotifier(
    private val smtpHost: String,
    private val smtpPort: Int,
    private val username: String?,
    private val password: String?,
    private val from: String,
    private val to: String
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort.toString())
            if (username != null) {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
            }
        }
        val session = if (username != null) {
            Session.getInstance(props, object : jakarta.mail.Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(username, password ?: "")
            })
        } else Session.getInstance(props)
        val fromAddr = InternetAddress(from) as jakarta.mail.Address
        val toAddr = InternetAddress(to) as jakarta.mail.Address
        val msg = MimeMessage(session).apply {
            setFrom(fromAddr)
            setRecipient(Message.RecipientType.TO, toAddr)
            subject = "[Koncerto] ${event.issueIdentifier}: ${event::class.simpleName}"
            setText(formatBody(event))
        }
        Transport.send(msg)
    }

    private fun formatBody(event: NotificationEvent): String = buildString {
        appendLine("Koncerto Notification")
        appendLine("Event: ${event::class.simpleName}")
        appendLine("Issue: ${event.issueIdentifier} - ${event.title}")
        appendLine("Project: ${event.projectSlug}")
        when (event) {
            is NotificationEvent.AgentFailed -> appendLine("Error: ${event.error}")
            is NotificationEvent.AgentStalled -> appendLine("Stall duration: ${event.stallDurationMs}ms")
            else -> {}
        }
    }
}
