package com.anomaly.koncerto.core.tenant

data class TenantContext(
    val tenantId: TenantId,
    val projectSlug: String,
    val tier: String = "standard",
    val quotaProfile: String = "default"
)
