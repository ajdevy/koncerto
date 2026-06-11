package com.anomaly.koncerto.core.ratelimit

data class RateLimitAlert(
    val providerKey: String,
    val limitType: String,
    val currentUsage: Int,
    val limit: Int,
    val thresholdPercent: Int,
    val timestamp: Long = System.currentTimeMillis()
)

interface RateLimitMonitor {
    fun checkAndAlert(stats: RateLimitStats, providerKey: String, thresholdPercent: Int): RateLimitAlert?
    fun getRecentAlerts(): List<RateLimitAlert>
    fun acknowledgeAlert(providerKey: String, limitType: String)
    fun clearAlerts()
}
