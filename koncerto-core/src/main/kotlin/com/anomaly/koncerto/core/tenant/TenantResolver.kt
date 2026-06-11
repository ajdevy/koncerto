package com.anomaly.koncerto.core.tenant

import com.anomaly.koncerto.core.config.ProjectConfig

interface TenantResolver {
    fun resolveTenant(projectSlug: String, config: ProjectConfig): TenantContext
}
