package com.anomaly.koncerto.notifications.channel

import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.notifications.Notifier
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class WebhookNotifier(
    private val url: String,
    private val headers: Map<String, String> = emptyMap()
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val body = Json.encodeToString(WebhookPayload(event))
        withContext(Dispatchers.IO) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}

@Serializable
private data class WebhookPayload(
    val event: String,
    val projectSlug: String,
    val issueId: String,
    val issueIdentifier: String,
    val title: String,
    val error: String? = null,
    val stallDurationMs: Long? = null
) {
    constructor(event: NotificationEvent) : this(
        event = event::class.simpleName ?: "unknown",
        projectSlug = event.projectSlug,
        issueId = event.issueId,
        issueIdentifier = event.issueIdentifier,
        title = event.title,
        error = (event as? NotificationEvent.AgentFailed)?.error,
        stallDurationMs = (event as? NotificationEvent.AgentStalled)?.stallDurationMs
    )
}
