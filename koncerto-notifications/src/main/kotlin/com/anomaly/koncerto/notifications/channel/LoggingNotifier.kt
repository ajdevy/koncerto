package com.anomaly.koncerto.notifications.channel

import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.notifications.NotificationEvent
import com.anomaly.koncerto.notifications.Notifier

class LoggingNotifier(
    private val logger: StructuredLogger
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        val ev = event::class.simpleName ?: "NotificationEvent"
        logger.info("notification_${ev.lowercase()}", mapOf(
            "issue_id" to event.issueId,
            "issue_identifier" to event.issueIdentifier
        ), "event" to ev)
    }
}
