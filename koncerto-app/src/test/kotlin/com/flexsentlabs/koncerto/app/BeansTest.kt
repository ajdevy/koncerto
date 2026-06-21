package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentHealthEndpoint
import com.flexsentlabs.koncerto.agent.AgentRuntimeFactory
import com.flexsentlabs.koncerto.agent.DefaultAgentHealthChecker
import com.flexsentlabs.koncerto.agent.DefaultSubtaskRunner
import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.core.CircuitBreaker
import com.flexsentlabs.koncerto.core.agent.AgentCircuitBreaker
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.NotificationsConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.errors.DefaultErrorTracker
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.ratelimit.DefaultRateLimitMonitor
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.logging.audit.FileAuditLogger
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.SqliteMetricsRepository
import com.flexsentlabs.koncerto.notifications.channel.LoggingNotifier
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.orchestrator.SubtaskFrontier
import com.flexsentlabs.koncerto.orchestrator.WorkplanParser
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.ShellHookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BeansTest {

    private val beans = Beans()
    private val logger = StructuredLogger(emptyList<LogSink>())

    @Test
    fun `logger creates instance without logsRoot`() {
        val logger = beans.logger(null)
        assertThat(logger).isNotNull()
    }

    @Test
    fun `logger creates instance with logsRoot`() {
        val tempDir = Files.createTempDirectory("koncerto-beans-test")
        try {
            val logger = beans.logger(tempDir.toString())
            assertThat(logger).isNotNull()
            assertThat(Files.exists(tempDir)).isTrue()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workflowCache returns a new instance`() {
        val cache = beans.workflowCache()
        assertThat(cache).isNotNull()
    }

    @Test
    fun `serviceConfig loads from a real workflow file`() {
        val workflowFile = Files.createTempFile("workflow", ".md")
        try {
            Files.writeString(
                workflowFile,
                """
                |---
                |poll_interval_ms: 15000
                |projects:
                |  test-project:
                |    tracker:
                |      kind: linear
                |      api_key: test-key-123
                |      project_slug: TEST
                |    workspace:
                |      root: /tmp/koncerto-test
                |    agent:
                |      kind: opencode
                |---
                |Test prompt body
                """.trimMargin()
            )

            val cache = WorkflowCache()
            val logger = StructuredLogger(emptyList<LogSink>())
            val config = beans.serviceConfig(workflowFile.toString(), cache, logger)

            assertThat(config).isNotNull()
            assertThat(config.pollIntervalMs).isEqualTo(15000L)
            assertThat(config.projects.size).isEqualTo(1)
        } finally {
            Files.deleteIfExists(workflowFile)
        }
    }

    @Test
    fun `configService returns a ConfigService`() {
        val cf = beans.configService("/path/to/workflow.md")
        assertThat(cf).isNotNull()
        assertThat(cf.getWorkflowPath()).isEqualTo("/path/to/workflow.md")
    }

    @Test
    fun `appScope returns a CoroutineScope`() {
        val scope = beans.appScope()
        assertThat(scope).isNotNull()
    }

    @Test
    fun `shellHookExecutor creates instance`() {
        val config = ServiceConfig(hooks = HooksConfig(null, null, null, null, 30000))
        val executor = beans.shellHookExecutor(config, logger)
        assertThat(executor).isNotNull()
    }

    @Test
    fun `workspaceManager creates instance with first project root`() {
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp/test-workspace"),
                agent = AgentProjectConfig(kind = "opencode")
            ))
        )
        val executor = ShellHookExecutor(60000, logger)
        val mgr = beans.workspaceManager(config, executor)
        assertThat(mgr).isNotNull()
    }

    @Test
    fun `workspaceManagerFactory returns lambda`() {
        val executor = ShellHookExecutor(60000, logger)
        val factory = beans.workspaceManagerFactory(executor)
        assertThat(factory).isNotNull()
        val result = factory(ProjectConfig(
            tracker = TrackerConfig("linear", "x", "k", "p"),
            workspace = WorkspaceConfig("/tmp/test"),
            agent = AgentProjectConfig(kind = "opencode")
        ))
        assertThat(result).isNotNull()
    }

    @Test
    fun `linearClientFactory returns lambda`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = beans.linearClientFactory(logger, scope)
        assertThat(factory).isNotNull()
        val client = factory(ProjectConfig(
            tracker = TrackerConfig("linear", "x", "k", "p"),
            workspace = WorkspaceConfig("/tmp"),
            agent = AgentProjectConfig(kind = "opencode")
        ))
        assertThat(client).isNotNull()
    }

    @Test
    fun `agentRuntimeFactory creates instance`() {
        val factory = beans.agentRuntimeFactory(logger)
        assertThat(factory).isNotNull()
    }

    @Test
    fun `freeModelCycler creates instance`() {
        val cycler = beans.freeModelCycler(logger)
        assertThat(cycler).isNotNull()
    }

    @Test
    fun `gitWorkflow creates instance`() {
        val config = ServiceConfig(gitConfig = GitConfig(enabled = false))
        val gw = beans.gitWorkflow(config, logger)
        assertThat(gw).isNotNull()
    }

    @Test
    fun `subtaskRunner creates instance`() {
        val runner = beans.subtaskRunner(logger, null)
        assertThat(runner).isNotNull()
    }

    @Test
    fun `subtaskFrontier creates instance`() {
        val frontier = beans.subtaskFrontier()
        assertThat(frontier).isNotNull()
    }

    @Test
    fun `workplanParser creates instance`() {
        val parser = beans.workplanParser(logger)
        assertThat(parser).isNotNull()
    }

    @Test
    fun `logNotifier creates instance`() {
        val notifier = beans.logNotifier(logger)
        assertThat(notifier).isNotNull()
    }

    @Test
    fun `agentHealthChecker creates instance`() {
        val checker = beans.agentHealthChecker()
        assertThat(checker).isNotNull()
    }

    @Test
    fun `agentHealthEndpoint creates instance`() {
        val checker = DefaultAgentHealthChecker()
        val endpoint = beans.agentHealthEndpoint(checker)
        assertThat(endpoint).isNotNull()
    }

    @Test
    fun `agentCircuitBreaker creates instance`() {
        val cb = beans.agentCircuitBreaker()
        assertThat(cb).isNotNull()
    }

    @Test
    fun `rateLimitMonitor creates instance`() {
        val monitor = beans.rateLimitMonitor()
        assertThat(monitor).isNotNull()
    }

    @Test
    fun `errorTracker creates instance`() {
        val tracker = beans.errorTracker()
        assertThat(tracker).isNotNull()
    }

    @Test
    fun `errorClassifier creates instance`() {
        val classifier = beans.errorClassifier()
        assertThat(classifier).isNotNull()
    }

    @Test
    fun `metricsRepository creates instance with in-memory db`() {
        val repo = beans.metricsRepository(":memory:")
        assertThat(repo).isNotNull()
    }

    @Test
    fun `prometheusMetricsBinder creates instance`() {
        val repo = SqliteMetricsRepository(":memory:")
        val binder = beans.prometheusMetricsBinder(repo)
        assertThat(binder).isNotNull()
    }

    @Test
    fun `runtimeStates returns map for projects`() {
        val config = ServiceConfig(
            projects = mapOf("proj-a" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode", maxConcurrentAgents = 5)
            ))
        )
        val states = beans.runtimeStates(config)
        assertThat(states).isNotNull()
        assertThat(states.size).isEqualTo(1)
        val state = states["proj-a"]
        assertThat(state).isNotNull()
        assertThat(state!!.pollIntervalMs).isEqualTo(config.pollIntervalMs)
        assertThat(state.maxConcurrentAgents).isEqualTo(5)
    }

    @Test
    fun `runtimeStates returns empty map for empty config`() {
        val config = ServiceConfig()
        val states = beans.runtimeStates(config)
        assertThat(states).isNotNull()
        assertThat(states.size).isEqualTo(0)
    }

    @Test
    fun `auditLogger creates instance`() {
        val tmpFile = Files.createTempFile("audit", ".log")
        Files.deleteIfExists(tmpFile)
        try {
            val auditLogger = beans.auditLogger(tmpFile.toString())
            assertThat(auditLogger).isNotNull()
        } finally {
            Files.deleteIfExists(tmpFile)
        }
    }

    @Test
    fun `compositeNotifier with no project config returns logging-only notifier`() {
        val config = ServiceConfig()
        val logNotifier = LoggingNotifier(logger)
        val result = beans.compositeNotifier(config, logNotifier)
        assertThat(result).isNotNull()
    }

    @Test
    fun `compositeNotifier with project notifications`() {
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode"),
                notifications = NotificationsConfig(
                    onCompleted = true,
                    webhook = com.flexsentlabs.koncerto.core.config.WebhookConfig(
                        url = "https://hook.example.com",
                        headers = mapOf("X-Api-Key" to "val")
                    ),
                    telegram = com.flexsentlabs.koncerto.core.config.TelegramConfig(
                        botToken = "bot:token", chatId = "-123"
                    ),
                    email = com.flexsentlabs.koncerto.core.config.EmailConfig(
                        smtpHost = "smtp.example.com", smtpPort = 587,
                        username = "user", password = "pass",
                        from = "from@ex.com", to = "to@ex.com"
                    )
                )
            ))
        )
        val logNotifier = LoggingNotifier(logger)
        val result = beans.compositeNotifier(config, logNotifier)
        assertThat(result).isNotNull()
    }

    @Test
    fun `modelRetryHandler throws when no projects`() {
        val config = ServiceConfig()
        val linearClientFactory: (ProjectConfig) -> LinearClient = {
            com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                "p"
            )
        }
        val logNotifier = LoggingNotifier(logger)
        val compositeNotifier = com.flexsentlabs.koncerto.notifications.CompositeNotifier(listOf(logNotifier))

        assertThrows<IllegalStateException> {
            beans.modelRetryHandler(config, linearClientFactory, compositeNotifier, logger)
        }
    }

    @Test
    fun `modelRetryHandler creates instance with project`() {
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode", maxTurns = 10)
            ))
        )
        val linearClientFactory: (ProjectConfig) -> LinearClient = {
            com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                "p"
            )
        }
        val logNotifier = LoggingNotifier(logger)
        val compositeNotifier = com.flexsentlabs.koncerto.notifications.CompositeNotifier(listOf(logNotifier))

        val handler = beans.modelRetryHandler(config, linearClientFactory, compositeNotifier, logger)
        assertThat(handler).isNotNull()
    }

    @Test
    fun `agentRunner creates instance`() {
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode")
            ))
        )
        val tempDir = Files.createTempDirectory("agent-runner-test")
        val hookExecutor = HookExecutor { _, _ -> }
        val workspaces = WorkspaceManager(tempDir, hookExecutor)
        val agentRuntimeFactory = AgentRuntimeFactory(logger)
        val gitWorkflow = GitWorkflow(GitConfig(false), logger)
        val runtimeStates = mapOf("test" to RuntimeState())
        val circuitBreaker = AgentCircuitBreaker()
        val errorTracker = DefaultErrorTracker()
        val healthChecker = DefaultAgentHealthChecker()
        val errorClassifier = PatternErrorClassifier()
        val freeModelCycler = FreeModelCycler.createDefault(logger)
        val logNotifier = LoggingNotifier(logger)
        val compositeNotifier = com.flexsentlabs.koncerto.notifications.CompositeNotifier(listOf(logNotifier))
        val linearClientFactory: (ProjectConfig) -> LinearClient = {
            com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                "p"
            )
        }
        val modelRetryHandler = com.flexsentlabs.koncerto.agent.ModelRetryHandler(
            cycler = freeModelCycler,
            projectConfig = config.projects.values.first(),
            linearClient = linearClientFactory(config.projects.values.first()),
            notifier = compositeNotifier,
            logger = logger
        )

        val runner = beans.agentRunner(
            config, workspaces, logger, agentRuntimeFactory, gitWorkflow,
            runtimeStates, circuitBreaker, errorTracker, healthChecker,
            errorClassifier, freeModelCycler, modelRetryHandler
        )
        assertThat(runner).isNotNull()
    }

    @Test
    fun `serviceConfig bean re-reads existing file`() {
        val workflowFile = Files.createTempFile("workflow-reload", ".md")
        try {
            Files.writeString(
                workflowFile,
                """
                |---
                |poll_interval_ms: 30000
                |projects:
                |  test-project:
                |    tracker:
                |      kind: linear
                |      api_key: test-key-456
                |      project_slug: TEST
                |    workspace:
                |      root: /tmp/koncerto-test
                |    agent:
                |      kind: opencode
                |---
                """.trimMargin()
            )

            val cache = WorkflowCache()
            val logger = StructuredLogger(emptyList<LogSink>())
            val config1 = beans.serviceConfig(workflowFile.toString(), cache, logger)
            assertThat(config1.pollIntervalMs).isEqualTo(30000L)

            val config2 = beans.serviceConfig(workflowFile.toString(), cache, logger)
            assertThat(config2.pollIntervalMs).isEqualTo(30000L)
        } finally {
            Files.deleteIfExists(workflowFile)
        }
    }

    @Test
    fun `projectRegistry returns new instance`() {
        val registry = beans.projectRegistry()
        assertThat(registry).isNotNull()
    }

    @Test
    fun `subtaskRunner creates instance with default runtimeFactory`() {
        val runner = beans.subtaskRunner(logger)
        assertThat(runner).isNotNull()
    }

    @Test
    fun `linearClientFactory lambda creates client`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = beans.linearClientFactory(logger, scope)
        val client = factory(ProjectConfig(
            tracker = TrackerConfig("linear", "x", "k", "p"),
            workspace = WorkspaceConfig("/tmp"),
            agent = AgentProjectConfig(kind = "opencode")
        ))
        assertThat(client).isNotNull()
    }

    @Test
    fun `subtaskOrchestrator creates instance`() {
        val tempDir = Files.createTempDirectory("subtask-orchestrator-test")
        val hookExecutor = HookExecutor { _, _ -> }
        val workspaces = WorkspaceManager(tempDir, hookExecutor)
        val gitWorkflow = GitWorkflow(GitConfig(false), logger)
        val subtaskRunner = DefaultSubtaskRunner(logger, null)
        val orchestrator = beans.subtaskOrchestrator(subtaskRunner, gitWorkflow, logger)
        assertThat(orchestrator).isNotNull()
    }

    @Test
    fun `orchestrator creates instance`() {
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode")
            ))
        )
        val tempDir = Files.createTempDirectory("orchestrator-test")
        val hookExecutor = HookExecutor { _, _ -> }
        val workspaces = WorkspaceManager(tempDir, hookExecutor)
        val agentRuntimeFactory = AgentRuntimeFactory(logger)
        val gitWorkflow = GitWorkflow(GitConfig(false), logger)
        val runtimeStates = mapOf("test" to RuntimeState())
        val circuitBreaker = AgentCircuitBreaker()
        val errorTracker = DefaultErrorTracker()
        val healthChecker = DefaultAgentHealthChecker()
        val errorClassifier = PatternErrorClassifier()
        val freeModelCycler = FreeModelCycler.createDefault(logger)
        val logNotifier = LoggingNotifier(logger)
        val compositeNotifier = com.flexsentlabs.koncerto.notifications.CompositeNotifier(listOf(logNotifier))
        val linearClientFactory: (ProjectConfig) -> LinearClient = {
            com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                "p"
            )
        }
        val modelRetryHandler = com.flexsentlabs.koncerto.agent.ModelRetryHandler(
            cycler = freeModelCycler,
            projectConfig = config.projects.values.first(),
            linearClient = linearClientFactory(config.projects.values.first()),
            notifier = compositeNotifier,
            logger = logger
        )
        val runner = beans.agentRunner(
            config, workspaces, logger, agentRuntimeFactory, gitWorkflow,
            runtimeStates, circuitBreaker, errorTracker, healthChecker,
            errorClassifier, freeModelCycler, modelRetryHandler
        )
        val cache = WorkflowCache()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager = { pc ->
            WorkspaceManager(Paths.get(pc.workspace.root), hookExecutor)
        }
        val metricsRepository = SqliteMetricsRepository(":memory:")
        val subtaskOrchestrator = beans.subtaskOrchestrator(DefaultSubtaskRunner(logger, null), gitWorkflow, logger)
        val workplanParser = WorkplanParser(logger)
        val auditLogger = FileAuditLogger(Files.createTempFile("audit", ".log"))

        val orchestrator = beans.orchestrator(
            config = config,
            runner = runner,
            cache = cache,
            logger = logger,
            scope = scope,
            linearClientFactory = linearClientFactory,
            workspaceManagerFactory = workspaceManagerFactory,
            runtimeStates = runtimeStates,
            metricsRepository = metricsRepository,
            compositeNotifier = compositeNotifier,
            subtaskOrchestrator = subtaskOrchestrator,
            workplanParser = workplanParser,
            auditLogger = auditLogger,
            demoEventListener = null
        )
        assertThat(orchestrator).isNotNull()
    }
}
