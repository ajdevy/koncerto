package com.anomaly.koncerto.core.tenant

import com.anomaly.koncerto.core.config.ProjectConfig

class ConfigTenantResolver : TenantResolver {

    override fun resolveTenant(projectSlug: String, config: ProjectConfig): TenantContext {
        val tenantConfig = config.tenant
        return if (tenantConfig != null) {
            TenantContext(
                tenantId = TenantId(projectSlug),
                projectSlug = projectSlug,
                tier = tenantConfig.tier,
                quotaProfile = tenantConfig.quotaProfile
            )
        } else {
            TenantContext(
                tenantId = TenantId("default"),
                projectSlug = projectSlug
            )
        }
    }
}
