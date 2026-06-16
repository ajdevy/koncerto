package com.flexsentlabs.koncerto.core.tenant

import com.flexsentlabs.koncerto.core.config.ProjectConfig

interface TenantResolver {
    fun resolveTenant(projectSlug: String, config: ProjectConfig): TenantContext
}
