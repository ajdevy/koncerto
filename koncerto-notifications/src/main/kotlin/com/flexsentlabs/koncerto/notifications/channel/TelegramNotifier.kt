package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class TelegramNotifier(
    private val botToken: String,
    private val chatId: String,
    /** Telegram API root. Overridable so tests can point at a local server instead of the network. */
    private val baseUrl: String = "https://api.telegram.org"
) : Notifier {
    // encodeDefaults is required: parse_mode has a default value, and kotlinx-serialization
    // omits defaults unless told otherwise. Without it Telegram never receives parse_mode and
    // renders the Markdown in formatTelegram() as literal asterisks.
    private val json = Json { encodeDefaults = true }

    override suspend fun send(event: NotificationEvent) {
        val text = formatTelegram(event)
        val payload = json.encodeToString(SendMessage(chatId = chatId, text = text))
        withContext(Dispatchers.IO) {
            val conn = URL("$baseUrl/bot$botToken/sendMessage").openConnection() as HttpURLConnection
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
    }

    private fun formatTelegram(event: NotificationEvent): String {
        val emoji = when (event) {
            is NotificationEvent.AgentCompleted -> "\u2705"
            is NotificationEvent.AgentFailed -> "\u274C"
            is NotificationEvent.AgentStalled -> "\u26A0\uFE0F"
            is NotificationEvent.ClarificationRequested -> "\u2753"
            is NotificationEvent.LimitDetected -> "\u26D4"
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
