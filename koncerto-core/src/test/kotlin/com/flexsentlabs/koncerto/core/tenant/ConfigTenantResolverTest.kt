package com.flexsentlabs.koncerto.core.tenant

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.TenantConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import org.junit.jupiter.api.Test

class ConfigTenantResolverTest {

    private val resolver = ConfigTenantResolver()

    private fun baseConfig(tenant: TenantConfig? = null): ProjectConfig {
        val tracker = TrackerConfig(
            kind = "linear",
            endpoint = "https://api.linear.app",
            apiKey = "key",
            projectSlug = "PROJ"
        )
        val workspace = WorkspaceConfig(root = "/tmp/ws")
        return ProjectConfig(
            tracker = tracker,
            workspace = workspace,
            agent = AgentProjectConfig(),
            tenant = tenant
        )
    }

    @Test
    fun `resolveTenant with tenant config returns configured tenant`() {
        val config = baseConfig(tenant = TenantConfig(tier = "enterprise", quotaProfile = "high"))
        val ctx = resolver.resolveTenant("PROJ", config)
        assertThat(ctx.tier).isEqualTo("enterprise")
        assertThat(ctx.quotaProfile).isEqualTo("high")
        assertThat(ctx.tenantId.value).isEqualTo("PROJ")
        assertThat(ctx.projectSlug).isEqualTo("PROJ")
    }

    @Test
    fun `resolveTenant without tenant config returns default`() {
        val config = baseConfig(tenant = null)
        val ctx = resolver.resolveTenant("OTHER", config)
        assertThat(ctx.tier).isEqualTo("standard")
        assertThat(ctx.quotaProfile).isEqualTo("default")
        assertThat(ctx.tenantId.value).isEqualTo("default")
        assertThat(ctx.projectSlug).isEqualTo("OTHER")
    }

    @Test
    fun `TenantId data class equality`() {
        val a = TenantId("tenant-a")
        val b = TenantId("tenant-a")
        val c = TenantId("tenant-b")
        assertThat(a).isEqualTo(b)
        assertThat(a == c).isEqualTo(false)
    }

    @Test
    fun `TenantContext data class with all fields`() {
        val ctx = TenantContext(
            tenantId = TenantId("t1"),
            projectSlug = "proj1",
            tier = "enterprise",
            quotaProfile = "high-throughput"
        )
        assertThat(ctx.tenantId.value).isEqualTo("t1")
        assertThat(ctx.projectSlug).isEqualTo("proj1")
        assertThat(ctx.tier).isEqualTo("enterprise")
        assertThat(ctx.quotaProfile).isEqualTo("high-throughput")
    }

    @Test
    fun `TenantContext data class with defaults`() {
        val ctx = TenantContext(
            tenantId = TenantId("t2"),
            projectSlug = "proj2"
        )
        assertThat(ctx.tenantId.value).isEqualTo("t2")
        assertThat(ctx.projectSlug).isEqualTo("proj2")
        assertThat(ctx.tier).isEqualTo("standard")
        assertThat(ctx.quotaProfile).isEqualTo("default")
    }
}
