package com.anomaly.koncerto.app

import com.anomaly.koncerto.core.config.AgentProjectConfig
import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.core.config.HooksConfig
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.core.config.TrackerConfig
import com.anomaly.koncerto.core.config.WorkspaceConfig
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.orchestrator.RunningEntry
import com.anomaly.koncerto.orchestrator.RuntimeState
import java.time.Instant
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@Profile("ui-test")
@TestConfiguration(proxyBeanMethods = false)
class DashboardUiTestConfig {

    @Bean
    fun uiTestRuntimeState(): RuntimeState {
        val state = RuntimeState()
        state.running["1"] = RunningEntry(
            issue = Issue(
                id = "1", identifier = "ABC-1", title = "Test issue",
                description = "A test issue", priority = 5, state = "In Progress",
                branchName = null, url = "https://linear.app/issue/ABC-1",
                labels = emptyList(), blockedBy = emptyList(),
                creator = null, createdAt = null, updatedAt = null
            ),
            threadId = "thread-1", turnId = "turn-1",
            startedAt = Instant.now(), lastCodexTimestamp = null,
            inputTokens = 100L, outputTokens = 50L, totalTokens = 150L,
            turnCount = 3
        )
        state.running["2"] = RunningEntry(
            issue = Issue(
                id = "2", identifier = "ABC-2", title = "Second issue",
                description = null, priority = 3, state = "Todo",
                branchName = null, url = null,
                labels = emptyList(), blockedBy = emptyList(),
                creator = null, createdAt = null, updatedAt = null
            ),
            threadId = "thread-2", turnId = "turn-2",
            startedAt = Instant.now(), lastCodexTimestamp = null,
            turnCount = 1
        )
        runBlocking {
            state.appendOutput("1", "[stdout] Initializing agent session...")
            state.appendOutput("1", "[stdout] Loading configuration")
            state.appendOutput("1", "[stderr] debug: workspace ready")
            state.appendOutput("1", "[stdout] Starting turn/start")
            state.appendOutput("1", "[stdout] Tool call: read_file")
            state.appendOutput("1", "[stderr] warn: file not found, creating")
            state.appendOutput("1", "[stdout] Tool call: write_file")
            state.appendOutput("1", "[stdout] Turn completed")
        }
        return state
    }

    @Bean
    fun uiTestRuntimeStates(): Map<String, RuntimeState> = mapOf("default" to uiTestRuntimeState())

    @Bean
    fun uiTestServiceConfig(): ServiceConfig = ServiceConfig(
        pollIntervalMs = 30000L,
        projects = mapOf("default" to ProjectConfig(
            tracker = TrackerConfig(
                kind = "linear", endpoint = "x", apiKey = "", projectSlug = "",
                requiredLabels = emptyList(), activeStates = emptyList(), terminalStates = emptyList(),
                blockedState = "Blocked", projectAdmin = null
            ),
            workspace = WorkspaceConfig(root = Paths.get(System.getProperty("java.io.tmpdir"), "ui-test").toString()),
            agent = AgentProjectConfig(
                kind = "opencode", command = "opencode",
                maxConcurrentAgents = 10, maxTurns = 20, maxRetryBackoffMs = 300000L,
                maxConcurrentAgentsByState = emptyMap(),
                turnTimeoutMs = 3600000L, readTimeoutMs = 5000L, stallTimeoutMs = 300000L,
                stages = emptyMap()
            )
        )),
        hooks = HooksConfig(null, null, null, null, 60000L),
        gitConfig = GitConfig()
    )
}
