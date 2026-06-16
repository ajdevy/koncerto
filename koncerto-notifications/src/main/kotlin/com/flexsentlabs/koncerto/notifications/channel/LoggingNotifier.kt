package com.flexsentlabs.koncerto.notifications.channel

import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.notifications.Notifier

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
