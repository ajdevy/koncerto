package com.flexsentlabs.koncerto.core.ratelimit

class DefaultRateLimitMonitor : RateLimitMonitor {
    private val alerts = java.util.concurrent.CopyOnWriteArrayList<RateLimitAlert>()
    private val acknowledged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    override fun checkAndAlert(stats: RateLimitStats, providerKey: String, thresholdPercent: Int): RateLimitAlert? {
        val minuteUsagePercent = if (stats.limitPerMinute > 0)
            (stats.requestsLastMinute * 100) / stats.limitPerMinute else 0
        val hourUsagePercent = if (stats.limitPerHour > 0)
            (stats.requestsLastHour * 100) / stats.limitPerHour else 0

        if (minuteUsagePercent >= thresholdPercent) {
            val alert = RateLimitAlert(providerKey, "minute", stats.requestsLastMinute, stats.limitPerMinute, thresholdPercent)
            val key = "${providerKey}:minute"
            if (!acknowledged.containsKey(key)) {
                alerts.add(alert)
                acknowledged[key] = true
            }
            return alert
        }
        if (hourUsagePercent >= thresholdPercent) {
            val alert = RateLimitAlert(providerKey, "hour", stats.requestsLastHour, stats.limitPerHour, thresholdPercent)
            val key = "${providerKey}:hour"
            if (!acknowledged.containsKey(key)) {
                alerts.add(alert)
                acknowledged[key] = true
            }
            return alert
        }
        return null
    }

    override fun getRecentAlerts(): List<RateLimitAlert> = alerts.toList()

    override fun acknowledgeAlert(providerKey: String, limitType: String) {
        acknowledged.remove("${providerKey}:${limitType}")
    }

    override fun clearAlerts() {
        alerts.clear()
        acknowledged.clear()
    }
}
