package com.flexsentlabs.koncerto.notifications

import java.util.concurrent.ConcurrentHashMap

class LimitCooldownTracker(
    private val cooldownMs: Long = 300_000L
) {
    private val lastSent = ConcurrentHashMap<String, Long>()

    fun shouldSend(errorType: String, issueId: String): Boolean {
        val key = "${errorType}:${issueId}"
        val now = System.currentTimeMillis()
        val last = lastSent[key]
        return if (last == null || (now - last) >= cooldownMs) {
            lastSent[key] = now
            true
        } else false
    }

    fun reset() {
        lastSent.clear()
    }
}
