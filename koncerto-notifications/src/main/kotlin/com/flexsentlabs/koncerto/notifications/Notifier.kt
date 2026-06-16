package com.flexsentlabs.koncerto.notifications

interface Notifier {
    suspend fun send(event: NotificationEvent)
}

class CompositeNotifier(
    private val notifiers: List<Notifier>
) : Notifier {
    override suspend fun send(event: NotificationEvent) {
        for (n in notifiers) {
            try { n.send(event) } catch (_: Exception) { }
        }
    }
}
