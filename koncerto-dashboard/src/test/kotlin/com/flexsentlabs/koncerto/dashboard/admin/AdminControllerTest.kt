package com.flexsentlabs.koncerto.dashboard.admin

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.metrics.IssueMetrics
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.TokenDaySummary
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private fun createConfig(adminKey: String? = "test-admin-key"): ServiceConfig {
    return ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf(
            "project-alpha" to ProjectConfig(
                tracker = TrackerConfig(
                    kind = "linear", endpoint = "https://api.linear.app/graphql",
                    apiKey = "key-1", projectSlug = "alpha",
                    projectAdmin = "admin@example.com"
                ),
                workspace = WorkspaceConfig(root = "/tmp/alpha"),
                agent = AgentProjectConfig(
                    kind = "opencode", command = "opencode",
                    maxConcurrentAgents = 2, maxTurns = 20, maxRetryBackoffMs = 300000,
                    maxConcurrentAgentsByState = emptyMap(),
                    turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                    stages = emptyMap()
                )
            ),
            "project-beta" to ProjectConfig(
                tracker = TrackerConfig(
                    kind = "linear", endpoint = "https://api.linear.app/graphql",
                    apiKey = "key-2", projectSlug = "beta",
                    projectAdmin = null
                ),
                workspace = WorkspaceConfig(root = "/tmp/beta"),
                agent = AgentProjectConfig(
                    kind = "custom", command = "custom-agent",
                    maxConcurrentAgents = 3, maxTurns = 10, maxRetryBackoffMs = 300000,
                    maxConcurrentAgentsByState = emptyMap(),
                    turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                    stages = emptyMap()
                )
            )
        ),
        hooks = HooksConfig(null, null, null, null, 60000),
        gitConfig = GitConfig(),
        adminApiKey = adminKey
    )
}

private class FakeMetricsRepository(private val allMetrics: List<IssueMetrics> = emptyList()) : MetricsRepository {
    override suspend fun updateAfterRun(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long
    ) = Unit

    override suspend fun findAll(): List<IssueMetrics> = allMetrics

    override suspend fun findByProject(projectSlug: String?): List<IssueMetrics> =
        allMetrics.filter { it.projectSlug == projectSlug }

    override suspend fun findById(issueId: String): IssueMetrics? = allMetrics.find { it.issueId == issueId }

    override suspend fun tokenHistory(days: Int): List<TokenDaySummary> = emptyList()
}

class AdminControllerTest {
    private lateinit var registry: ProjectRegistry

    @BeforeEach
    fun setUp() {
        registry = ProjectRegistry()
        registry.registerProject("project-alpha", createConfig().projects["project-alpha"]!!)
        registry.registerProject("project-beta", createConfig().projects["project-beta"]!!)
    }

    @Test
    fun `listProjects returns project list with valid key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listProjects("test-admin-key") }

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body
        assertThat(body).isNotNull()
        assertThat(body!!).hasSize(2)
        assertThat(body[0].slug).isEqualTo("project-alpha")
        assertThat(body[0].agentKind).isEqualTo("opencode")
        assertThat(body[1].slug).isEqualTo("project-beta")
        assertThat(body[1].agentKind).isEqualTo("custom")
    }

    @Test
    fun `listProjects returns 401 without key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listProjects(null) }

        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listProjects returns 401 with wrong key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listProjects("wrong-key") }

        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listProjects with metrics returns active counts`() {
        val metrics = listOf(
            IssueMetrics("1", "ABC-1", "alpha", 5, 100, 50, 150, "SUCCESS", "2024-01-01", "2024-01-01", "2024-01-10"),
            IssueMetrics("2", "DEF-2", "alpha", 3, 200, 100, 300, "FAILED", "2024-02-01", "2024-02-01", "2024-02-10"),
            IssueMetrics("3", "GHI-3", "beta", 1, 50, 25, 75, "SUCCESS", "2024-03-01", "2024-03-01", "2024-03-10")
        )
        val config = createConfig()
        val controller = AdminController(config, FakeMetricsRepository(metrics), registry)
        val response = runBlocking { controller.listProjects("test-admin-key") }

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body!!
        assertThat(body.find { it.slug == "project-alpha" }!!.activeCount).isEqualTo(2)
        assertThat(body.find { it.slug == "project-beta" }!!.activeCount).isEqualTo(1)
    }

    @Test
    fun `getProjectDetail returns details for valid slug`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        val detail = response.body!!
        assertThat(detail.slug).isEqualTo("project-alpha")
        assertThat(detail.tracker.kind).isEqualTo("linear")
        assertThat(detail.tracker.projectSlug).isEqualTo("alpha")
        assertThat(detail.tracker.projectAdmin).isEqualTo("admin@example.com")
        assertThat(detail.agent.kind).isEqualTo("opencode")
        assertThat(detail.agent.maxConcurrentAgents).isEqualTo(2)
        assertThat(detail.workspace.root).isEqualTo("/tmp/alpha")
    }

    @Test
    fun `getProjectDetail returns 401 without key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", null).block()

        assertThat(response!!.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `getProjectDetail returns 404 for invalid slug`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("nonexistent", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(404)
    }

    @Test
    fun `listTenants returns deduplicated tenants`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listTenants("test-admin-key") }

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body!!
        assertThat(body).hasSize(2)
        val alpha = body.find { it.tenantSlug == "alpha" }
        assertThat(alpha).isNotNull()
        assertThat(alpha!!.projectCount).isEqualTo(1)
        assertThat(alpha.agentKinds.filter { it == "opencode" }.single()).isEqualTo("opencode")
    }

    @Test
    fun `listTenants returns 401 without key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listTenants(null) }
        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listTenants returns 401 with wrong key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listTenants("wrong-key") }
        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listQuotas returns 401 without key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listQuotas(null) }
        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listQuotas returns 401 with wrong key`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listQuotas("wrong-key") }
        assertThat(response.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `all admin endpoints return 401 when adminApiKey is empty string`() {
        val config = createConfig(adminKey = "")
        val controller = AdminController(config, projectRegistry = null)
        // An empty-string key must be treated as absent — any caller who sends X-Admin-Key: "" must be rejected
        val projectsResponse = runBlocking { controller.listProjects("") }
        assertThat(projectsResponse.statusCodeValue).isEqualTo(401)
        val tenantsResponse = runBlocking { controller.listTenants("") }
        assertThat(tenantsResponse.statusCodeValue).isEqualTo(401)
        val quotasResponse = runBlocking { controller.listQuotas("") }
        assertThat(quotasResponse.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `all admin endpoints return 401 when adminApiKey is null`() {
        val config = createConfig(adminKey = null)
        val controller = AdminController(config, projectRegistry = null)
        val projectsResponse = runBlocking { controller.listProjects("any-key") }
        assertThat(projectsResponse.statusCodeValue).isEqualTo(401)
        val tenantsResponse = runBlocking { controller.listTenants("any-key") }
        assertThat(tenantsResponse.statusCodeValue).isEqualTo(401)
        val quotasResponse = runBlocking { controller.listQuotas("any-key") }
        assertThat(quotasResponse.statusCodeValue).isEqualTo(401)
        val detailResponse = controller.getProjectDetail("project-alpha", "any-key").block()
        assertThat(detailResponse!!.statusCodeValue).isEqualTo(401)
    }

    @Test
    fun `listQuotas returns quota usage from metrics`() {
        val metrics = listOf(
            IssueMetrics("1", "ABC-1", "alpha", 5, 100, 50, 150, "SUCCESS", "2024-01-01", "2024-01-01", "2024-01-10"),
            IssueMetrics("2", "DEF-2", "beta", 3, 200, 100, 300, "FAILED", "2024-02-01", "2024-02-01", "2024-02-10")
        )
        val config = createConfig()
        val controller = AdminController(config, FakeMetricsRepository(metrics), registry)
        val response = runBlocking { controller.listQuotas("test-admin-key") }

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body!!
        assertThat(body).hasSize(2)
        assertThat(body.find { it.projectSlug == "alpha" }!!.totalTokens).isEqualTo(150)
        assertThat(body.find { it.projectSlug == "alpha" }!!.totalRuns).isEqualTo(1)
        assertThat(body.find { it.projectSlug == "beta" }!!.totalTokens).isEqualTo(300)
        assertThat(body.find { it.projectSlug == "beta" }!!.totalRuns).isEqualTo(1)
    }

    @Test
    fun `getProjectDetail returns all tracker fields including projectSlug and projectAdmin`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        val detail = response.body!!
        assertThat(detail.tracker.projectSlug).isEqualTo("alpha")
        assertThat(detail.tracker.projectAdmin).isEqualTo("admin@example.com")
        assertThat(detail.tracker.kind).isEqualTo("linear")
    }

    @Test
    fun `getProjectDetail returns all agent fields including kind`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        val detail = response.body!!
        assertThat(detail.agent.kind).isEqualTo("opencode")
        assertThat(detail.agent.maxConcurrentAgents).isEqualTo(2)
        assertThat(detail.agent.maxTurns).isEqualTo(20)
        assertThat(detail.agent.stages).isEmpty()
    }

    @Test
    fun `getProjectDetail returns all workspace fields`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        val detail = response.body!!
        assertThat(detail.workspace.root).isEqualTo("/tmp/alpha")
    }

    @Test
    fun `getProjectDetail returns all notifications fields`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = controller.getProjectDetail("project-alpha", "test-admin-key").block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        val detail = response.body!!
        assertThat(detail.notifications.onCompleted).isFalse()
        assertThat(detail.notifications.onFailed).isFalse()
        assertThat(detail.notifications.onStalled).isFalse()
    }

    @Test
    fun `listProjects returns projectSlug and projectAdmin in summary`() {
        val config = createConfig()
        val controller = AdminController(config, projectRegistry = registry)
        val response = runBlocking { controller.listProjects("test-admin-key") }

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body!!
        val alpha = body.find { it.slug == "project-alpha" }!!
        assertThat(alpha.projectSlug).isEqualTo("alpha")
        assertThat(alpha.projectAdmin).isEqualTo("admin@example.com")
        val beta = body.find { it.slug == "project-beta" }!!
        assertThat(beta.projectSlug).isEqualTo("beta")
        assertThat(beta.projectAdmin).isNull()
    }
}
