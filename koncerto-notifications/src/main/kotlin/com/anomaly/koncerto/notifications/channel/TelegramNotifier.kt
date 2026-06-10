package com.anomaly.koncerto.notifications.channel

import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.notifications.Notifier
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class TelegramNotifier(
    private val botToken: String,
    private val chatId: String
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val text = formatTelegram(event)
        val payload = Json.encodeToString(SendMessage(chatId = chatId, text = text))
        val conn = URL("https://api.telegram.org/bot$botToken/sendMessage").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        try {
            OutputStreamWriter(conn.outputStream).use { it.write(payload) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun formatTelegram(event: NotificationEvent): String {
        val emoji = when (event) {
            is NotificationEvent.AgentCompleted -> "\u2705"
            is NotificationEvent.AgentFailed -> "\u274C"
            is NotificationEvent.AgentStalled -> "\u26A0\uFE0F"
            is NotificationEvent.ClarificationRequested -> "\u2753"
        }
        return "$emoji *${event.issueIdentifier}*: ${event.title}"
    }
}

@Serializable
private data class SendMessage(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("parse_mode") val parseMode: String = "Markdown"
)
