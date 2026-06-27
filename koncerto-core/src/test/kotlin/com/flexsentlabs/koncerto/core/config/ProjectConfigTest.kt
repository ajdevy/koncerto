package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class ProjectConfigTest {

    @Test
    fun `ProjectConfig data class structural equality`() {
        val tracker = TrackerConfig(
            kind = "linear",
            endpoint = "https://api.linear.app",
            apiKey = "key123",
            projectSlug = "PROJ"
        )
        val workspace = WorkspaceConfig(root = "/tmp/workspace")
        val agent = AgentProjectConfig()
        val config = ProjectConfig(tracker = tracker, workspace = workspace, agent = agent)
        val same = ProjectConfig(tracker = tracker, workspace = workspace, agent = agent)
        assertThat(config).isEqualTo(same)
    }

    @Test
    fun `TrackerConfig with all fields`() {
        val tc = TrackerConfig(
            kind = "github",
            endpoint = "https://api.github.com",
            apiKey = "ghp_xxx",
            projectSlug = "my/repo",
            requiredLabels = listOf("bug"),
            activeStates = listOf("open"),
            terminalStates = listOf("closed"),
            blockedState = "blocked",
            projectAdmin = "admin"
        )
        assertThat(tc.kind).isEqualTo("github")
        assertThat(tc.endpoint).isEqualTo("https://api.github.com")
        assertThat(tc.apiKey).isEqualTo("ghp_xxx")
        assertThat(tc.projectSlug).isEqualTo("my/repo")
        assertThat(tc.requiredLabels).isEqualTo(listOf("bug"))
        assertThat(tc.activeStates).isEqualTo(listOf("open"))
        assertThat(tc.terminalStates).isEqualTo(listOf("closed"))
        assertThat(tc.blockedState).isEqualTo("blocked")
        assertThat(tc.projectAdmin).isEqualTo("admin")
    }

    @Test
    fun `WorkspaceConfig with root`() {
        val ws = WorkspaceConfig(root = "/projects/myapp")
        assertThat(ws.root).isEqualTo("/projects/myapp")
    }

    @Test
    fun `AgentProjectConfig with defaults`() {
        val cfg = AgentProjectConfig()
        assertThat(cfg.kind).isEqualTo("opencode")
        assertThat(cfg.maxConcurrentAgents).isEqualTo(2)
        assertThat(cfg.maxTurns).isEqualTo(20)
        assertThat(cfg.maxRetries).isEqualTo(3)
        assertThat(cfg.sequentialMode).isEqualTo(false)
    }

    @Test
    fun `AgentProjectConfig with custom values`() {
        val cfg = AgentProjectConfig(
            kind = "custom",
            command = "my-agent",
            sequentialMode = true,
            maxConcurrentAgents = 5,
            maxTurns = 50,
            maxRetries = 7
        )
        assertThat(cfg.kind).isEqualTo("custom")
        assertThat(cfg.command).isEqualTo("my-agent")
        assertThat(cfg.sequentialMode).isEqualTo(true)
        assertThat(cfg.maxConcurrentAgents).isEqualTo(5)
        assertThat(cfg.maxTurns).isEqualTo(50)
        assertThat(cfg.maxRetries).isEqualTo(7)
    }

    @Test
    fun `NotificationsConfig with defaults`() {
        val nc = NotificationsConfig()
        assertThat(nc.onCompleted).isEqualTo(true)
        assertThat(nc.onFailed).isEqualTo(true)
        assertThat(nc.onStalled).isEqualTo(true)
        assertThat(nc.onClarification).isEqualTo(true)
    }

    @Test
    fun `NotificationsConfig with custom values`() {
        val nc = NotificationsConfig(
            onCompleted = false,
            onFailed = false,
            onStalled = true,
            onClarification = false
        )
        assertThat(nc.onCompleted).isEqualTo(false)
        assertThat(nc.onFailed).isEqualTo(false)
        assertThat(nc.onStalled).isEqualTo(true)
        assertThat(nc.onClarification).isEqualTo(false)
    }

    @Test
    fun `TelegramConfig data class`() {
        val tg = TelegramConfig(botToken = "bot:xxx", chatId = "-12345")
        assertThat(tg.botToken).isEqualTo("bot:xxx")
        assertThat(tg.chatId).isEqualTo("-12345")
    }

    @Test
    fun `EmailConfig data class`() {
        val email = EmailConfig(
            smtpHost = "smtp.example.com",
            smtpPort = 465,
            username = "user",
            password = "pass",
            from = "from@example.com",
            to = "to@example.com"
        )
        assertThat(email.smtpHost).isEqualTo("smtp.example.com")
        assertThat(email.smtpPort).isEqualTo(465)
        assertThat(email.username).isEqualTo("user")
        assertThat(email.from).isEqualTo("from@example.com")
        assertThat(email.to).isEqualTo("to@example.com")
    }

    @Test
    fun `WebhookConfig data class`() {
        val wh = WebhookConfig(
            url = "https://hooks.example.com/alert",
            headers = mapOf("Authorization" to "Bearer xxx")
        )
        assertThat(wh.url).isEqualTo("https://hooks.example.com/alert")
        assertThat(wh.headers).isEqualTo(mapOf("Authorization" to "Bearer xxx"))
    }

    @Test
    fun `RateLimiterConfig data class`() {
        val rl = RateLimiterConfig(requestsPerSecond = 5, maxBurst = 10)
        assertThat(rl.requestsPerSecond).isEqualTo(5)
        assertThat(rl.maxBurst).isEqualTo(10)
    }

    @Test
    fun `CircuitBreakerConfig data class`() {
        val cb = CircuitBreakerConfig(failureThreshold = 3, resetTimeoutMs = 15_000)
        assertThat(cb.failureThreshold).isEqualTo(3)
        assertThat(cb.resetTimeoutMs).isEqualTo(15_000)
    }

    @Test
    fun `RateLimitsConfig data class`() {
        val agentLimit = RateLimitConfig(requestsPerMinute = 30)
        val rl = RateLimitsConfig(agent = agentLimit)
        assertThat(rl.agent).isNotNull()
        assertThat(rl.agent!!.requestsPerMinute).isEqualTo(30)
        assertThat(rl.linear).isNull()
        assertThat(rl.github).isNull()
    }

    @Test
    fun `RateLimitConfig data class`() {
        val rlc = RateLimitConfig(
            requestsPerMinute = 10,
            requestsPerHour = 500,
            burstCapacity = 5,
            backoffMs = 2000
        )
        assertThat(rlc.requestsPerMinute).isEqualTo(10)
        assertThat(rlc.requestsPerHour).isEqualTo(500)
        assertThat(rlc.burstCapacity).isEqualTo(5)
        assertThat(rlc.backoffMs).isEqualTo(2000)
    }

    @Test
    fun `RoutingRule with required fields only`() {
        val rule = RoutingRule(useAgent = "claude")
        assertThat(rule.useAgent).isEqualTo("claude")
        assertThat(rule.ifLabel).isNull()
        assertThat(rule.ifLabelPrefix).isNull()
        assertThat(rule.ifState).isNull()
        assertThat(rule.ifPriority).isNull()
        assertThat(rule.ifPriorityMax).isNull()
        assertThat(rule.priority).isEqualTo(0)
    }

    @Test
    fun `RoutingRule with all fields`() {
        val rule = RoutingRule(
            ifLabel = "bug",
            ifLabelPrefix = "area/",
            ifState = "Todo",
            ifPriority = 1,
            ifPriorityMax = 5,
            useAgent = "gpt4",
            priority = 10
        )
        assertThat(rule.ifLabel).isEqualTo("bug")
        assertThat(rule.ifLabelPrefix).isEqualTo("area/")
        assertThat(rule.ifState).isEqualTo("Todo")
        assertThat(rule.ifPriority).isEqualTo(1)
        assertThat(rule.ifPriorityMax).isEqualTo(5)
        assertThat(rule.useAgent).isEqualTo("gpt4")
        assertThat(rule.priority).isEqualTo(10)
    }

    @Test
    fun `TenantConfig with defaults`() {
        val tc = TenantConfig()
        assertThat(tc.tier).isEqualTo("standard")
        assertThat(tc.quotaProfile).isEqualTo("default")
    }

    @Test
    fun `TenantConfig with custom values`() {
        val tc = TenantConfig(tier = "enterprise", quotaProfile = "high-throughput")
        assertThat(tc.tier).isEqualTo("enterprise")
        assertThat(tc.quotaProfile).isEqualTo("high-throughput")
    }

    @Test
    fun `DockerConfig data class`() {
        val dc = DockerConfig(enabled = true, image = "my-agent:latest", cpu = "2", memory = "4g")
        assertThat(dc.enabled).isEqualTo(true)
        assertThat(dc.image).isEqualTo("my-agent:latest")
        assertThat(dc.cpu).isEqualTo("2")
        assertThat(dc.memory).isEqualTo("4g")
    }

    @Test
    fun `WorkplanConfig data class`() {
        val wc = WorkplanConfig(
            executionMode = WorkplanConfig.ExecutionMode.PARALLEL,
            maxParallelSubagents = 5
        )
        assertThat(wc.executionMode).isEqualTo(WorkplanConfig.ExecutionMode.PARALLEL)
        assertThat(wc.maxParallelSubagents).isEqualTo(5)
    }

    @Test
    fun `WorkplanConfig with defaults`() {
        val wc = WorkplanConfig()
        assertThat(wc.executionMode).isEqualTo(WorkplanConfig.ExecutionMode.SEQUENTIAL)
        assertThat(wc.maxParallelSubagents).isEqualTo(3)
    }
}
