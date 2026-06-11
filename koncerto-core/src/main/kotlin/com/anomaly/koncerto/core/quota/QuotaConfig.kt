package com.anomaly.koncerto.core.quota

import kotlinx.serialization.Serializable

@Serializable
data class QuotaConfig(
    val maxConcurrentAgents: Int = 5,
    val maxRateLimit: Int = 100,
    val maxWorkspaceStorageMB: Long = 1024
) {
    fun isUnlimited() = maxConcurrentAgents < 0
}
