package com.flexsentlabs.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentAuthChecker
import com.flexsentlabs.koncerto.agent.ClaudeAuthSupport
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private object LoginTestHelper {
    private val controllerClass = ApiV1Controller::class.java

    private fun setField(name: String, value: Any?) {
        val field = controllerClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(null, value)
    }

    private fun getField(name: String): Any? {
        val field = controllerClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null)
    }

    fun reset() {
        ClaudeAuthSupport.clearToken()
        (getField("codexProcess") as? Process)?.destroyForcibly()
        (getField("claudeProcess") as? Process)?.destroyForcibly()
        setField("codexProcess", null)
        setField("codexUrl", null)
        setField("codexCode", null)
        setField("claudeProcess", null)
        setField("claudeUrl", null)
        setField("claudeCode", null)
        setField("claudeLoginOutput", null)
    }

    fun setCodexState(process: Process?, url: String?, code: String?) {
        setField("codexProcess", process)
        setField("codexUrl", url)
        setField("codexCode", code)
    }

    fun setClaudeState(process: Process?, url: String?, code: String?, output: String?) {
        setField("claudeProcess", process)
        setField("claudeUrl", url)
        setField("claudeCode", code)
        setField("claudeLoginOutput", output)
    }
}

private fun createOrchestrator(
    config: ServiceConfig,
    state: RuntimeState,
    slug: String = "default"
): Orchestrator {
    val fakeClient = object : LinearClient {
        override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) = emptyList<Issue>()
        override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) = emptyList<Issue>()
        override suspend fun fetchIssueStatesByIds(issueIds: List<String>) = emptyMap<String, String>()
        override suspend fun fetchIssueById(issueId: String) = null
        override suspend fun resolveStateId(projectSlug: String, stateName: String) = null
        override suspend fun updateIssueState(issueId: String, stateId: String) {}
        override suspend fun createComment(issueId: String, body: String) {}
        override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
        override suspend fun fetchIssueCreator(issueId: String) = null
        override suspend fun createIssue(
            projectSlug: String, title: String, state: String,
            description: String?, labels: List<String>
        ): Issue? = null
        override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
    }
    return Orchestrator(
        config = config,
        linearClientFactory = { fakeClient },
        workspaceManagerFactory = { WorkspaceManager(Paths.get("/tmp"), HookExecutor { _, _ -> }) },
        agentRunner = object : AgentRunner {
            override fun events(): Flow<AgentEvent> = MutableSharedFlow<AgentEvent>().asSharedFlow()
            override suspend fun run(
                issue: Issue, attempt: Int?, prompt: String,
                agentKindOverride: String?, commandOverride: String?,
                modelOverride: String?,
                effortOverride: String?,
                turnTimeoutMs: Long?, stallTimeoutMs: Long?
            ): EmptyResult<IllegalStateException> = Result.Success(Unit)
        },
        workflowCache = WorkflowCache(),
        logger = StructuredLogger(emptyList()),
        scope = CoroutineScope(Job() + Dispatchers.Unconfined),
        runtimeStates = mapOf(slug to state),
        metricsRepository = null
    )
}

private fun minimalConfig(stages: Map<String, StageAgentConfig> = emptyMap()) = ServiceConfig(
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
            stages = stages
        )
    )),
    hooks = HooksConfig(null, null, null, null, 60000),
    gitConfig = GitConfig()
)

private fun createController(config: ServiceConfig = minimalConfig()): ApiV1Controller {
    val state = RuntimeState()
    return ApiV1Controller(config, createOrchestrator(config, state))
}

private fun longRunningProcess(): Process =
    ProcessBuilder("sleep", "300").start()

class ApiV1ControllerAuthTest {

    @BeforeEach
    fun setUp() {
        LoginTestHelper.reset()
        AgentAuthChecker.reset()
        AgentAuthChecker.markUnauthenticated("claude")
        AgentAuthChecker.markUnauthenticated("codex")
    }

    @AfterEach
    fun tearDown() {
        ApiV1Controller.testLoginProcessFactory = null
        LoginTestHelper.reset()
        AgentAuthChecker.reset()
    }

    @Test
    fun `getAgentAuthStatus returns default agents when no stages configured`() {
        val controller = createController()
        val response = controller.getAgentAuthStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        val body = response.body!!
        assertThat(body.map { it.agent }).isEqualTo(listOf("codex", "opencode", "claude"))
        assertThat(body.find { it.agent == "opencode" }!!.needsAuth).isFalse()
        assertThat(body.find { it.agent == "opencode" }!!.authenticated).isTrue()
    }

    @Test
    fun `getAgentAuthStatus returns configured agent kinds from stages`() {
        val stages = mapOf(
            "todo" to StageAgentConfig(
                prompt = "p", model = null, effort = null, maxConcurrent = null,
                agentKind = "claude", command = null, onCompleteState = null
            )
        )
        val controller = createController(minimalConfig(stages))
        val response = controller.getAgentAuthStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        val agents = response.body!!.map { it.agent }
        assertThat(agents).isEqualTo(listOf("claude"))
    }

    @Test
    fun `getCodexLoginStatus returns idle when no process`() {
        val controller = createController()
        val response = controller.getCodexLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("idle")
    }

    @Test
    fun `getCodexLoginStatus returns pending when process alive`() {
        val process = longRunningProcess()
        LoginTestHelper.setCodexState(process, "https://auth.example.com", "ABCD-1234")
        val controller = createController()
        val response = controller.getCodexLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.url).isEqualTo("https://auth.example.com")
        assertThat(response.body!!.code).isEqualTo("ABCD-1234")
    }

    @Test
    fun `getCodexLoginStatus returns completed when process finished`() {
        val process = ProcessBuilder("true").start()
        process.waitFor()
        LoginTestHelper.setCodexState(process, null, null)
        val controller = createController()
        val response = controller.getCodexLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("completed")
        assertThat(response.body!!.authenticated).isTrue()
    }

    @Test
    fun `cancelCodexLogin returns ok and clears state`() {
        val process = longRunningProcess()
        LoginTestHelper.setCodexState(process, "https://auth.example.com", "ABCD-1234")
        val controller = createController()
        val response = controller.cancelCodexLogin()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(controller.getCodexLoginStatus().body!!.state).isEqualTo("idle")
    }

    @Test
    fun `startCodexLogin returns pending when process already alive with url`() {
        val process = longRunningProcess()
        LoginTestHelper.setCodexState(process, "https://existing.example.com", "WXYZ-5678")
        val controller = createController()
        val response = controller.startCodexLogin().block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.url).isEqualTo("https://existing.example.com")
    }

    @Test
    fun `startCodexLogin returns 409 when process alive without url`() {
        val process = longRunningProcess()
        LoginTestHelper.setCodexState(process, null, null)
        val controller = createController()
        val response = controller.startCodexLogin().block()

        assertThat(response!!.statusCodeValue).isEqualTo(409)
    }

    @Test
    fun `startClaudeLogin returns pending when process already alive with url`() {
        val process = longRunningProcess()
        LoginTestHelper.setClaudeState(process, "https://claude.example.com", "CLDE-5678", null)
        val controller = createController()
        val response = controller.startClaudeLogin().block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.url).isEqualTo("https://claude.example.com")
    }

    @Test
    fun `cancelCodexLogin returns ok when no process running`() {
        val controller = createController()
        val response = controller.cancelCodexLogin()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(controller.getCodexLoginStatus().body!!.state).isEqualTo("idle")
    }

    @Test
    fun `getClaudeLoginStatus returns completed when already authenticated`() {
        AgentAuthChecker.markAuthenticated("claude")
        val controller = createController()
        val response = controller.getClaudeLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("completed")
    }

    @Test
    fun `getClaudeLoginStatus returns idle when no process`() {
        val controller = createController()
        val response = controller.getClaudeLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("idle")
    }

    @Test
    fun `getClaudeLoginStatus returns pending when process alive`() {
        val process = longRunningProcess()
        LoginTestHelper.setClaudeState(process, "https://claude.example.com", "CLDE-4321", null)
        val controller = createController()
        val response = controller.getClaudeLoginStatus()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.url).isEqualTo("https://claude.example.com")
    }

    @Test
    fun `getClaudeLoginStatus extracts token when process finished`() {
        val output = """
            Your OAuth token (valid for 1 year):
            sk-ant-oat01-test-token-value
            Store this token securely.
        """.trimIndent()
        val process = ProcessBuilder("true").start()
        process.waitFor()
        LoginTestHelper.setClaudeState(process, null, null, output)
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = java.nio.file.Files.createTempDirectory("claude-auth-dashboard-")
        val tokenPath = dir.resolve("token.txt")
        try {
            System.setProperty("koncerto.claude.auth.token.path", tokenPath.toString())
            AgentAuthChecker.reset()
            val controller = createController()
            val response = controller.getClaudeLoginStatus()

            assertThat(response.statusCodeValue).isEqualTo(200)
            assertThat(response.body!!.state).isEqualTo("completed")
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
        } finally {
            if (original == null) {
                System.clearProperty("koncerto.claude.auth.token.path")
            } else {
                System.setProperty("koncerto.claude.auth.token.path", original)
            }
        }
    }

    @Test
    fun `startClaudeLogin returns completed when already authenticated`() {
        AgentAuthChecker.markAuthenticated("claude")
        val controller = createController()
        val response = controller.startClaudeLogin().block()

        assertThat(response!!.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("completed")
    }

    @Test
    fun `startClaudeLogin returns 409 when process alive without url`() {
        val process = longRunningProcess()
        LoginTestHelper.setClaudeState(process, null, null, null)
        val controller = createController()
        val response = controller.startClaudeLogin().block()

        assertThat(response!!.statusCodeValue).isEqualTo(409)
    }

    @Test
    fun `submitClaudeCode returns error when code missing`() {
        val controller = createController()
        val response = controller.submitClaudeCode(emptyMap())

        assertThat(response.statusCodeValue).isEqualTo(400)
        assertThat(response.body!!.state).isEqualTo("error")
    }

    @Test
    fun `submitClaudeCode returns no_process when no login running`() {
        val controller = createController()
        val response = controller.submitClaudeCode(mapOf("code" to "123456"))

        assertThat(response.statusCodeValue).isEqualTo(400)
        assertThat(response.body!!.state).isEqualTo("no_process")
    }

    @Test
    fun `submitClaudeCode returns process_exited when process dead`() {
        val process = ProcessBuilder("true").start()
        process.waitFor()
        LoginTestHelper.setClaudeState(process, null, null, null)
        val controller = createController()
        val response = controller.submitClaudeCode(mapOf("code" to "123456"))

        assertThat(response.statusCodeValue).isEqualTo(400)
        assertThat(response.body!!.state).isEqualTo("process_exited")
    }

    @Test
    fun `submitClaudeCode writes code to process stdin`() {
        val process = ProcessBuilder("cat").start()
        LoginTestHelper.setClaudeState(process, "https://claude.example.com", "CLDE-0000", null)
        val controller = createController()
        val response = controller.submitClaudeCode(mapOf("code" to "my-auth-code"))

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        process.destroyForcibly()
    }

    @Test
    fun `cancelClaudeLogin returns ok and clears state`() {
        val process = longRunningProcess()
        LoginTestHelper.setClaudeState(process, "https://claude.example.com", "CLDE-0000", "output")
        val controller = createController()
        val response = controller.cancelClaudeLogin()

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(controller.getClaudeLoginStatus().body!!.state).isEqualTo("idle")
    }

    @Test
    fun `withTimeout reads output containing one-time code phrase`() {
        val controller = createController()
        val method = ApiV1Controller::class.java.getDeclaredMethod(
            "withTimeout", java.io.InputStream::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        val input = java.io.ByteArrayInputStream("Enter your one-time code below\n".toByteArray())
        val text = method.invoke(controller, input, 1000L) as String
        assertThat(text.contains("one-time code")).isTrue()
    }

    @Test
    fun `testLoginProcessFactory seam roundtrips`() {
        ApiV1Controller.testLoginProcessFactory = { cmd ->
            ProcessBuilder("bash", "-c", "echo seam:$cmd")
        }
        assertThat(ApiV1Controller.testLoginProcessFactory).isNotNull()
        ApiV1Controller.testLoginProcessFactory = null
        assertThat(ApiV1Controller.testLoginProcessFactory).isNull()
    }

    @Test
    fun `startCodexLogin extracts url and code from login output`() {
        ApiV1Controller.testLoginProcessFactory = { _ ->
            ProcessBuilder(
                "bash", "-c",
                "echo 'Visit https://auth.example.com/device and enter code ABCD-1234'; sleep 0.2"
            )
        }
        val controller = createController()
        val response = controller.startCodexLogin().block()!!

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.url).isEqualTo("https://auth.example.com/device")
        assertThat(response.body!!.code).isEqualTo("ABCD-1234")
    }

    @Test
    fun `startClaudeLogin extracts localhost callback port from output`() {
        ApiV1Controller.testLoginProcessFactory = { _ ->
            ProcessBuilder(
                "bash", "-c",
                "echo 'Open http://localhost:8765/callback with code CLDE-9999 https://claude.example.com'; sleep 0.2"
            )
        }
        val controller = createController()
        val response = controller.startClaudeLogin().block()!!

        assertThat(response.statusCodeValue).isEqualTo(200)
        assertThat(response.body!!.state).isEqualTo("pending")
        assertThat(response.body!!.code).isEqualTo("CLDE-9999")
        assertThat(ApiV1Controller.getClaudeCallbackPort()).isEqualTo(8765)
    }

    @Test
    fun `startCodexLogin returns error when login process fails to start`() {
        ApiV1Controller.testLoginProcessFactory = { _ ->
            throw RuntimeException("login failed")
        }
        val controller = createController()
        val response = controller.startCodexLogin().block()!!

        assertThat(response.statusCodeValue).isEqualTo(500)
        assertThat(response.body!!.state).isEqualTo("error")
    }

    @Test
    fun `withTimeout stops early when https url appears in stream`() {
        val controller = createController()
        val method = ApiV1Controller::class.java.getDeclaredMethod(
            "withTimeout", java.io.InputStream::class.java, Long::class.javaPrimitiveType
        )
        method.isAccessible = true
        val input = java.io.ByteArrayInputStream("Go to https://device.example.com now\n".toByteArray())
        val text = method.invoke(controller, input, 5000L) as String
        assertThat(text.contains("https://device.example.com")).isTrue()
    }
}
