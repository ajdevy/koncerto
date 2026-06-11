package com.anomaly.koncerto.core.tenant

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.TenantConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.config.AgentProjectConfig
import org.junit.jupiter.api.Test

class TenantResolverTest {

    private val resolver = ConfigTenantResolver()

    private fun baseProjectConfig() = ProjectConfig(
        tracker = TrackerConfig(
            kind = "linear", endpoint = "x", apiKey = "k", projectSlug = "p"
        ),
        workspace = WorkspaceConfig(root = "/tmp"),
        agent = AgentProjectConfig()
    )

    @Test
    fun `resolves tenant from config with tenant block`() {
        val config = baseProjectConfig().copy(
            tenant = TenantConfig(tier = "enterprise", quotaProfile = "large")
        )
        val context = resolver.resolveTenant("my-project", config)
        assertThat(context.tenantId.value).isEqualTo("my-project")
        assertThat(context.projectSlug).isEqualTo("my-project")
        assertThat(context.tier).isEqualTo("enterprise")
        assertThat(context.quotaProfile).isEqualTo("large")
    }

    @Test
    fun `returns default tenant when no tenant block`() {
        val config = baseProjectConfig()
        val context = resolver.resolveTenant("my-project", config)
        assertThat(context.tenantId.value).isEqualTo("default")
        assertThat(context.projectSlug).isEqualTo("my-project")
        assertThat(context.tier).isEqualTo("standard")
        assertThat(context.quotaProfile).isEqualTo("default")
    }

    @Test
    fun `tenant id value class wraps correctly`() {
        val id = TenantId("tenant-abc")
        assertThat(id.value).isEqualTo("tenant-abc")
    }
}
