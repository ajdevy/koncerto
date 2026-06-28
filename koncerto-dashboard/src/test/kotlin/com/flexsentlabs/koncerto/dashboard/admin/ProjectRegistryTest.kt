package com.flexsentlabs.koncerto.dashboard.admin

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProjectRegistryTest {
    private lateinit var registry: ProjectRegistry
    private val testConfig = ProjectConfig(
        tracker = TrackerConfig(
            kind = "linear", endpoint = "https://api.linear.app/graphql",
            apiKey = "key", projectSlug = "slug"
        ),
        workspace = WorkspaceConfig(root = "/tmp/test"),
        agent = AgentProjectConfig(
            kind = "opencode", command = "opencode",
            maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300000,
            maxConcurrentAgentsByState = emptyMap(),
            turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
            stages = emptyMap()
        )
    )

    @BeforeEach
    fun setUp() {
        registry = ProjectRegistry()
    }

    @Test
    fun `getAllProjects returns registered projects`() {
        registry.registerProject("test", testConfig)
        val all = registry.getAllProjects()
        assertThat(all).hasSize(1)
        assertThat(all["test"]).isNotNull()
    }

    @Test
    fun `getProjectCount returns correct count after registration`() {
        assertThat(registry.getProjectCount()).isEqualTo(0)
        registry.registerProject("test", testConfig)
        assertThat(registry.getProjectCount()).isEqualTo(1)
        registry.registerProject("test-2", testConfig)
        assertThat(registry.getProjectCount()).isEqualTo(2)
    }

    @Test
    fun `getProject returns null for unregistered slug`() {
        assertThat(registry.getProject("nonexistent")).isNull()
    }

    @Test
    fun `getProject returns registered config`() {
        registry.registerProject("test", testConfig)
        val config = registry.getProject("test")
        assertThat(config).isNotNull()
        assertThat(config!!.tracker.kind).isEqualTo("linear")
    }

    @Test
    fun `registerProject overwrites existing entry`() {
        registry.registerProject("test", testConfig)
        val updated = testConfig.copy(
            workspace = WorkspaceConfig(root = "/tmp/updated")
        )
        registry.registerProject("test", updated)
        assertThat(registry.getProjectCount()).isEqualTo(1)
        assertThat(registry.getProject("test")!!.workspace.root).isEqualTo("/tmp/updated")
    }

    @Test
    fun `getAllProjects returns snapshot not live view`() {
        registry.registerProject("test", testConfig)
        val snapshot = registry.getAllProjects()
        registry.registerProject("other", testConfig)
        assertThat(snapshot).hasSize(1)
        assertThat(registry.getProjectCount()).isEqualTo(2)
    }
}
