package com.anomaly.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.config.AgentProjectConfig
import com.anomaly.koncerto.metrics.IssueMetrics
import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.metrics.TokenDaySummary
import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.StageAgentConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.orchestrator.RetryEntry
import com.anomaly.koncerto.orchestrator.RunningEntry
import com.anomaly.koncerto.orchestrator.RuntimeState
import com.anomaly.koncerto.orchestrator.TokenTotals
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ApiV1ControllerTest {

    private fun minimalConfig() = ServiceConfig(
        pollIntervalMs = 30000,
        projects = mapOf("default" to ProjectConfig(
            tracker = TrackerConfig(
                kind = "", endpoint = "x", apiKey = "", projectSlug = "",
                requiredLabels = emptyList(), activeStates = emptyList(), terminalStates = emptyList()
            ),
            workspace = WorkspaceConfig(root = "/tmp"),
            agent = AgentProjectConfig(
                kind = "opencode", command = "opencode",
                maxConcurrentAgents = 1, maxTurns = 1, maxRetryBackoffMs = 300000,
                maxConcurrentAgentsByState = emptyMap(),
                turnTimeoutMs = 3600000, readTimeoutMs = 5000, stallTimeoutMs = 300000,
                stages = emptyMap()
            )
        )),
        hooks = HooksConfig(null, null, null, null, 60000),
        gitConfig = GitConfig()
    )

    @Test
    fun `state returns snapshot with running and retrying`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5

        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            inputTokens = 100,
            outputTokens = 50,
            totalTokens = 150,
            turnCount = 2
        )
        state.retryAttempts["2"] = RetryEntry("2", "ABC-2", 1, System.currentTimeMillis() + 60000, "timeout")

        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val snapshot = controller.state().block()

        assertThat(snapshot!!.running.size).isEqualTo(1)
        assertThat(snapshot.running[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(snapshot.running[0].threadId).isEqualTo("t-1")
        assertThat(snapshot.running[0].turnCount).isEqualTo(2)
        assertThat(snapshot.running[0].inputTokens).isEqualTo(100)
        assertThat(snapshot.retrying.size).isEqualTo(1)
        assertThat(snapshot.retrying[0].identifier).isEqualTo("ABC-2")
        assertThat(snapshot.retrying[0].attempt).isEqualTo(1)
    }

    @Test
    fun `byIdentifier returns issue details when found`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            turnCount = 3
        )

        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val result = controller.byIdentifier("ABC-1").block()

        assertThat(result!!.issueId).isEqualTo("1")
        assertThat(result.issueIdentifier).isEqualTo("ABC-1")
        assertThat(result.threadId).isEqualTo("t-1")
        assertThat(result.turnCount).isEqualTo(3)
    }

    @Test
    fun `byIdentifier returns not_found when missing`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val result = controller.byIdentifier("MISSING").block()

        assertThat(result!!.error).isEqualTo("not_found")
    }

    @Test
    fun `refresh returns ok`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val result = controller.refresh().block()

        assertThat(result!!.status).isEqualTo("ok")
    }

    @Test
    fun `models returns configured stages`() {
        val state = RuntimeState()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "impl.md", model = "claude-sonnet", maxConcurrent = null,
                agentKind = "opencode", command = null, onCompleteState = null
            )
        )
        val pc = minimalConfig().projects["default"]!!
        val config = minimalConfig().copy(
            projects = mapOf("default" to pc.copy(
                agent = pc.agent.copy(kind = "opencode", stages = stages)
            ))
        )
        val controller = ApiV1Controller(config, mapOf("default" to state))
        val result = controller.models().block()

        assertThat(result!!.agentKind).isEqualTo("opencode")
        assertThat(result.totalStages).isEqualTo(1)
        assertThat(result.configuredStages[0].stage).isEqualTo("todo")
        assertThat(result.configuredStages[0].model).isEqualTo("claude-sonnet")
        assertThat(result.configuredStages[0].agentKind).isEqualTo("opencode")
    }

    @Test
    fun `models returns empty list when no stages configured`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val result = controller.models().block()

        assertThat(result!!.totalStages).isEqualTo(0)
        assertThat(result.configuredStages).isEqualTo(emptyList())
    }

    @Test
    fun `history returns all metrics`() {
        val repo = FakeMetricsRepository()
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state), repo)
        val result = runBlocking { controller.history(null, 50) }
        assertThat(result.size).isEqualTo(2)
        assertThat(result[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(result[1].issueIdentifier).isEqualTo("DEF-2")
    }

    @Test
    fun `history filters by project`() {
        val repo = FakeMetricsRepository()
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state), repo)
        val result = runBlocking { controller.history("project-x", 50) }
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0].projectSlug).isEqualTo("project-x")
    }

    @Test
    fun `history returns empty when no metrics`() {
        val repo = FakeMetricsRepository(emptyList())
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state), repo)
        val result = runBlocking { controller.history(null, 50) }
        assertThat(result).isEmpty()
    }

    @Test
    fun `stages returns per-project stage config`() {
        val state = RuntimeState()
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "impl.md", model = "claude-sonnet", maxConcurrent = 3,
                agentKind = "opencode", command = null, onCompleteState = "In Progress"
            )
        )
        val pc = minimalConfig().projects["default"]!!
        val config = minimalConfig().copy(
            projects = mapOf("default" to pc.copy(
                agent = pc.agent.copy(kind = "opencode", maxConcurrentAgents = 5, stages = stages)
            ))
        )
        val controller = ApiV1Controller(config, mapOf("default" to state))
        val result = controller.stages()
        assertThat(result.size).isEqualTo(1)
        @Suppress("UNCHECKED_CAST")
        val defaultEntry = result["default"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val agent = defaultEntry["agent"] as Map<String, Any>
        assertThat(agent["kind"]).isEqualTo("opencode")
        assertThat(agent["maxConcurrent"]).isEqualTo(5)
        @Suppress("UNCHECKED_CAST")
        val stagesMap = defaultEntry["stages"] as Map<String, Map<String, Any?>>
        assertThat(stagesMap["todo"]?.get("prompt")).isEqualTo("impl.md")
        assertThat(stagesMap["todo"]?.get("onCompleteState")).isEqualTo("In Progress")
    }

    @Test
    fun `pauseAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastCodexTimestamp = null
        )
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val response = controller.pauseAgent("ABC-1")
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running["1"]?.paused).isEqualTo(true)
    }

    @Test
    fun `resumeAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastCodexTimestamp = null, paused = true
        )
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val response = controller.resumeAgent("ABC-1")
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running["1"]?.paused).isEqualTo(false)
    }

    @Test
    fun `cancelAgent returns 200 for existing identifier`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null, null)
        state.running["1"] = RunningEntry(
            issue = issue, threadId = "t-1", turnId = "u-1",
            startedAt = Instant.now(), lastCodexTimestamp = null
        )
        state.claimed.add("1")
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val response = controller.cancelAgent("ABC-1")
        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(state.running.containsKey("1")).isFalse()
        assertThat(state.claimed.contains("1")).isFalse()
    }

    @Test
    fun `pauseAgent returns 404 for unknown identifier`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(minimalConfig(), mapOf("default" to state))
        val response = controller.pauseAgent("UNKNOWN")
        assertThat(response.statusCodeValue).isEqualTo(404)
    }
}

private class FakeMetricsRepository(private val allMetrics: List<IssueMetrics> = listOf(
    IssueMetrics("1", "ABC-1", null, 5, 100, 50, 150, "SUCCESS", "2024-01-01", "2024-01-01", "2024-01-10"),
    IssueMetrics("2", "DEF-2", "project-x", 3, 200, 100, 300, "FAILED", "2024-02-01", "2024-02-01", "2024-02-10")
)) : MetricsRepository {
    override suspend fun updateAfterRun(issueId: String, issueIdentifier: String, projectSlug: String?, result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long) = Unit
    override suspend fun findAll(): List<IssueMetrics> = allMetrics
    override suspend fun findByProject(projectSlug: String?): List<IssueMetrics> = allMetrics.filter { it.projectSlug == projectSlug }
    override suspend fun findById(issueId: String): IssueMetrics? = allMetrics.find { it.issueId == issueId }
    override suspend fun tokenHistory(days: Int): List<TokenDaySummary> = emptyList()
}
