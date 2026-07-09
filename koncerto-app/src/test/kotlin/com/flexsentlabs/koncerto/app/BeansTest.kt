package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentHealthEndpoint
import com.flexsentlabs.koncerto.agent.AgentRuntimeFactory
import com.flexsentlabs.koncerto.agent.DefaultAgentHealthChecker
import com.flexsentlabs.koncerto.agent.DefaultSubtaskRunner
import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.core.CircuitBreaker
import com.flexsentlabs.koncerto.core.agent.AgentCircuitBreaker
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.CircuitBreakerConfig
import com.flexsentlabs.koncerto.core.config.DemoRecordingConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.NotificationsConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.RateLimitConfig
import com.flexsentlabs.koncerto.core.config.RateLimiterConfig
import com.flexsentlabs.koncerto.core.config.RateLimitsConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.errors.DefaultErrorTracker
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.ratelimit.DefaultRateLimitMonitor
import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.recorder.AdbRecorder
import com.flexsentlabs.koncerto.demo.recorder.AsciinemaRecorder
import com.flexsentlabs.koncerto.demo.recorder.FfmpegRecorder
import com.flexsentlabs.koncerto.demo.recorder.PlaywrightRecorder
import com.flexsentlabs.koncerto.demo.recorder.XcrunRecorder
import com.flexsentlabs.koncerto.deploy.DockerLaunchCleaner
import com.flexsentlabs.koncerto.deploy.OrphanedContainerCleanupScheduler
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.linear.RateLimitedLinearClient
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.logging.audit.FileAuditLogger
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.SqliteMetricsRepository
import com.flexsentlabs.koncerto.notifications.channel.LoggingNotifier
import com.flexsentlabs.koncerto.orchestrator.AutoReviewOrchestrator
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
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
            demoEventListener = null,
            targetProjectDeployer = beans.targetProjectDeployer(logger)
        )
        assertThat(orchestrator).isNotNull()
    }

    @Test
    fun `orchestrator wires the same targetProjectDeployer singleton into AutoReviewOrchestrator`() {
        // Regression test for a real production bug: Beans is @Configuration(proxyBeanMethods
        // = false), so calling targetProjectDeployer(logger) directly inside the
        // autoReviewOrchestratorFactory lambda (instead of receiving it as an injected
        // parameter) silently built a BRAND NEW TargetProjectDeployer, disconnected from the
        // singleton the orphan-container cleanup scheduler holds. That meant an in-flight-
        // deployment guard on one instance was invisible to the periodic sweep running against
        // the other, and the sweep force-removed a container mid-recording. Fixed by adding
        // targetProjectDeployer as a real @Bean-injected parameter and threading that same
        // reference through to AutoReviewOrchestrator.
        val stageConfig = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = null, command = null, onCompleteState = null
        )
        val config = ServiceConfig(
            projects = mapOf("test" to ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode", stages = mapOf("in review" to stageConfig))
            ))
        )
        val tempDir = Files.createTempDirectory("orchestrator-deployer-wiring-test")
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
        val theSingleton = beans.targetProjectDeployer(logger)

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
            demoEventListener = null,
            targetProjectDeployer = theSingleton
        )

        val factoryField = Orchestrator::class.java.getDeclaredField("autoReviewOrchestratorFactory")
        factoryField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val factory = factoryField.get(orchestrator)
            as (ProjectConfig, RuntimeState) -> AutoReviewOrchestrator
        val autoReview = factory(config.projects.values.first(), RuntimeState())

        val deployerField = AutoReviewOrchestrator::class.java.getDeclaredField("targetProjectDeployer")
        deployerField.isAccessible = true
        val wiredDeployer = deployerField.get(autoReview)

        assertThat(wiredDeployer === theSingleton).isTrue()
    }

    @Test
    fun `logger creates instance with blank logsRoot`() {
        assertThat(beans.logger("")).isNotNull()
    }

    @Test
    fun `linearClientFactory with rate limiter returns RateLimitedLinearClient`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = beans.linearClientFactory(logger, scope)
        val client = factory(
            ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode"),
                rateLimiter = RateLimiterConfig(requestsPerSecond = 5, maxBurst = 10),
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, resetTimeoutMs = 5_000)
            )
        )
        assertThat(client).isInstanceOf(RateLimitedLinearClient::class)
    }

    @Test
    fun `linearClientFactory with rateLimits linear config`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = beans.linearClientFactory(logger, scope)
        val client = factory(
            ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", "p"),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode"),
                rateLimits = RateLimitsConfig(
                    linear = RateLimitConfig(
                        requestsPerMinute = 60,
                        requestsPerHour = 1000,
                        burstCapacity = 20,
                        backoffMs = 1000
                    )
                )
            )
        )
        assertThat(client).isNotNull()
    }

    @Test
    fun `linearClientFactory creates client even with empty project slug`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = beans.linearClientFactory(logger, scope)
        val client = factory(
            ProjectConfig(
                tracker = TrackerConfig("linear", "x", "k", ""),
                workspace = WorkspaceConfig("/tmp"),
                agent = AgentProjectConfig(kind = "opencode")
            )
        )
        assertThat(client).isNotNull()
    }

    @Test
    fun `workspaceManager throws when no projects`() {
        val executor = ShellHookExecutor(60_000, logger)
        assertThrows<IllegalStateException> {
            beans.workspaceManager(ServiceConfig(), executor)
        }
    }

    @Test
    fun `compositeNotifier with webhook only`() {
        val config = ServiceConfig(
            projects = mapOf(
                "test" to ProjectConfig(
                    tracker = TrackerConfig("linear", "x", "k", "p"),
                    workspace = WorkspaceConfig("/tmp"),
                    agent = AgentProjectConfig(kind = "opencode"),
                    notifications = NotificationsConfig(
                        onCompleted = true,
                        webhook = com.flexsentlabs.koncerto.core.config.WebhookConfig(
                            url = "https://hook.example.com",
                            headers = mapOf("Authorization" to "Bearer token")
                        )
                    )
                )
            )
        )
        assertThat(beans.compositeNotifier(config, LoggingNotifier(logger))).isNotNull()
    }

    @Test
    fun `compositeNotifier with telegram only`() {
        val config = ServiceConfig(
            projects = mapOf(
                "test" to ProjectConfig(
                    tracker = TrackerConfig("linear", "x", "k", "p"),
                    workspace = WorkspaceConfig("/tmp"),
                    agent = AgentProjectConfig(kind = "opencode"),
                    notifications = NotificationsConfig(
                        onCompleted = true,
                        telegram = com.flexsentlabs.koncerto.core.config.TelegramConfig(
                            botToken = "bot:123",
                            chatId = "-456"
                        )
                    )
                )
            )
        )
        assertThat(beans.compositeNotifier(config, LoggingNotifier(logger))).isNotNull()
    }

    @Test
    fun `compositeNotifier with email only`() {
        val config = ServiceConfig(
            projects = mapOf(
                "test" to ProjectConfig(
                    tracker = TrackerConfig("linear", "x", "k", "p"),
                    workspace = WorkspaceConfig("/tmp"),
                    agent = AgentProjectConfig(kind = "opencode"),
                    notifications = NotificationsConfig(
                        onCompleted = true,
                        email = com.flexsentlabs.koncerto.core.config.EmailConfig(
                            smtpHost = "smtp.example.com",
                            smtpPort = 465,
                            username = "user",
                            password = "pass",
                            from = "from@example.com",
                            to = "to@example.com"
                        )
                    )
                )
            )
        )
        assertThat(beans.compositeNotifier(config, LoggingNotifier(logger))).isNotNull()
    }

    @Test
    fun `serviceConfig logs deprecation warnings`() {
        val workflowFile = Files.createTempFile("workflow-deprecation", ".md")
        try {
            Files.writeString(
                workflowFile,
                """
                |---
                |polling:
                |  interval_ms: 12000
                |projects:
                |  test-project:
                |    tracker:
                |      kind: linear
                |      api_key: test-key
                |      project_slug: TEST
                |    workspace:
                |      root: /tmp/koncerto-test
                |    agent:
                |      kind: opencode
                |---
                """.trimMargin()
            )
            val cache = WorkflowCache()
            val config = beans.serviceConfig(workflowFile.toString(), cache, logger)
            assertThat(config.pollIntervalMs).isEqualTo(12_000L)
            assertThat(config.deprecationWarnings).isNotNull()
            assertThat(config.deprecationWarnings.isNotEmpty()).isTrue()
        } finally {
            Files.deleteIfExists(workflowFile)
        }
    }

    @Test
    fun `orchestrator with in review stage and git remote url`() {
        val config = ServiceConfig(
            gitConfig = GitConfig(enabled = false, remoteUrl = "git@github.com:owner/repo.git"),
            projects = mapOf(
                "test" to ProjectConfig(
                    tracker = TrackerConfig("linear", "x", "k", "p"),
                    workspace = WorkspaceConfig("/tmp"),
                    agent = AgentProjectConfig(
                        kind = "opencode",
                        stages = mapOf(
                            "in review" to StageAgentConfig(
                                prompt = "review.md",
                                model = null,
                                effort = null,
                                maxConcurrent = null,
                                agentKind = "claude",
                                command = null,
                                onCompleteState = "Ready for Human Review"
                            )
                        )
                    )
                )
            )
        )
        assertThat(createOrchestratorForConfig(config)).isNotNull()
    }

    @Test
    fun `orchestrator resolves repo from workspace git config when remote url blank`() {
        val wsRoot = Files.createTempDirectory("beans-ws-git")
        try {
            val gitDir = wsRoot.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(
                gitDir.resolve("config"),
                """
                |[remote "origin"]
                |    url = git@github.com:ws-owner/ws-repo.git
                """.trimMargin()
            )
            val config = ServiceConfig(
                gitConfig = GitConfig(enabled = false, remoteUrl = ""),
                projects = mapOf(
                    "test" to ProjectConfig(
                        tracker = TrackerConfig("linear", "x", "k", "p"),
                        workspace = WorkspaceConfig(wsRoot.toString()),
                        agent = AgentProjectConfig(
                            kind = "opencode",
                            stages = mapOf(
                                "in review" to StageAgentConfig(
                                    prompt = "review.md",
                                    model = null,
                                    effort = null,
                                    maxConcurrent = null,
                                    agentKind = "claude",
                                    command = null,
                                    onCompleteState = "Ready for Human Review"
                                )
                            )
                        )
                    )
                )
            )
            assertThat(createOrchestratorForConfig(config)).isNotNull()
        } finally {
            wsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `demoConfig maps service demo recording settings`() {
        val config = ServiceConfig(
            demoRecording = DemoRecordingConfig(
                enabled = true,
                targetUrl = "http://localhost:8080",
                cleanupIntervalHours = 12
            )
        )
        val demoConfig = beans.demoConfig(config)
        assertThat(demoConfig.enabled).isTrue()
        assertThat(demoConfig.targetUrl).isEqualTo("http://localhost:8080")
        assertThat(demoConfig.cleanupIntervalHours).isEqualTo(12)
    }

    @Test
    fun `demoConfig maps r2 storage and ai settings from service config`() {
        val config = ServiceConfig(
            demoRecording = DemoRecordingConfig(
                enabled = true,
                targetUrl = "http://demo.example.com",
                storage = DemoRecordingConfig.StorageConfig(
                    r2Endpoint = "https://r2.example.com",
                    r2Bucket = "demos",
                    r2AccessKey = "access",
                    r2SecretKey = "secret",
                    publicUrlBase = "https://cdn.example.com",
                    presignedUrlTtl = 3600,
                    region = "auto"
                ),
                ai = DemoRecordingConfig.AiConfig(
                    model = "claude-sonnet",
                    timeline = true,
                    reproSteps = true
                ),
                retry = DemoRecordingConfig.RetryConfig(maxAttempts = 5),
                platform = DemoRecordingConfig.PlatformConfig(web = "playwright", terminal = "asciinema")
            )
        )
        val demoConfig = beans.demoConfig(config)
        assertThat(demoConfig.r2).isNotNull()
        assertThat(demoConfig.r2!!.endpoint).isEqualTo("https://r2.example.com")
        assertThat(demoConfig.r2!!.bucketName).isEqualTo("demos")
        assertThat(demoConfig.ai!!.model).isEqualTo("claude-sonnet")
        assertThat(demoConfig.ai!!.timelineEnabled).isTrue()
        assertThat(demoConfig.ai!!.reproStepsEnabled).isTrue()
        assertThat(demoConfig.maxRetries).isEqualTo(5)
        assertThat(demoConfig.defaultPlatform).isEqualTo("playwright")
    }

    @Test
    fun `demoCleanupScheduler returns null when recording service missing`() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        assertThat(beans.demoCleanupScheduler(null, DemoConfig(enabled = true), scope)).isNull()
    }

    @Test
    fun `demoStorage returns null without r2 config`() {
        assertThat(beans.demoStorage(DemoConfig(enabled = false))).isNull()
    }

    @Test
    fun `demoStorage creates R2 storage when configured`() {
        val demoConfig = DemoConfig(
            r2 = DemoConfig.R2Config(
                endpoint = "https://r2.example.com",
                accessKey = "key",
                secretKey = "secret",
                bucketName = "bucket",
                publicUrlBase = "https://cdn.example.com"
            )
        )
        assertThat(beans.demoStorage(demoConfig)).isNotNull()
    }

    @Test
    fun `demoReporter returns null when no project configured`() {
        val factory: (ProjectConfig) -> LinearClient = { throw AssertionError("should not be called") }
        assertThat(beans.demoReporter(factory, ServiceConfig())).isNull()
    }

    @Test
    fun `demoReporter creates publisher when project exists`() {
        val config = ServiceConfig(
            projects = mapOf(
                "test" to ProjectConfig(
                    tracker = TrackerConfig("linear", "x", "k", "p"),
                    workspace = WorkspaceConfig("/tmp"),
                    agent = AgentProjectConfig(kind = "opencode")
                )
            )
        )
        val factory: (ProjectConfig) -> LinearClient = {
            com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                "p"
            )
        }
        assertThat(beans.demoReporter(factory, config)).isNotNull()
    }

    @Test
    fun `recorder beans create instances`() {
        assertThat(beans.playwrightRecorder()).isInstanceOf(PlaywrightRecorder::class)
        assertThat(beans.asciinemaRecorder()).isInstanceOf(AsciinemaRecorder::class)
        assertThat(beans.adbRecorder()).isInstanceOf(AdbRecorder::class)
        assertThat(beans.xcrunRecorder()).isInstanceOf(XcrunRecorder::class)
        assertThat(beans.ffmpegRecorder()).isInstanceOf(FfmpegRecorder::class)
    }

    @Test
    fun `recorderFactory creates factory from recorders`() {
        val recorders = listOf(
            beans.playwrightRecorder(),
            beans.asciinemaRecorder(),
            beans.adbRecorder()
        )
        assertThat(beans.recorderFactory(recorders)).isNotNull()
    }

    @Test
    fun `demoTaskRepository creates sqlite repository`() {
        assertThat(beans.demoTaskRepository(":memory:")).isNotNull()
    }

    @Test
    fun `demoRecordingService returns null when storage missing`() {
        val demoConfig = beans.demoConfig(ServiceConfig())
        val taskRepository = beans.demoTaskRepository(":memory:")
        val recorderFactory = beans.recorderFactory(listOf(beans.playwrightRecorder()))
        val reporter = beans.demoReporter(
            {
                com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                    com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                    "p"
                )
            },
            ServiceConfig(
                projects = mapOf(
                    "test" to ProjectConfig(
                        tracker = TrackerConfig("linear", "x", "k", "p"),
                        workspace = WorkspaceConfig("/tmp"),
                        agent = AgentProjectConfig(kind = "opencode")
                    )
                )
            )
        )
        val service = beans.demoRecordingService(
            demoConfig = demoConfig,
            demoTaskRepository = taskRepository,
            recorderFactory = recorderFactory,
            demoStorage = null,
            demoReporter = reporter,
            demoReportGenerator = beans.demoReportGenerator(),
            demoMetricsRecorder = beans.demoMetricsRecorder(),
            demoAuditLogger = beans.demoAuditLogger(),
            aiTimelineGenerator = beans.aiTimelineGenerator(demoConfig)
        )
        assertThat(service).isNull()
    }

    @Test
    fun `demoController returns null when recording service missing`() {
        assertThat(beans.demoController(null)).isNull()
    }

    @Test
    fun `demoEventListener returns null when recording service missing`() {
        assertThat(beans.demoEventListener(null, DemoConfig())).isNull()
    }

    @Test
    fun `aiTimelineGenerator creates generator for demo config with ai`() {
        val demoConfig = DemoConfig(ai = DemoConfig.AiConfig(model = "free"))
        assertThat(beans.aiTimelineGenerator(demoConfig)).isNotNull()
    }

    @Test
    fun `targetProjectDeployer and demoFailureReporter create instances`() {
        assertThat(beans.targetProjectDeployer(logger)).isNotNull()
        assertThat(beans.demoFailureReporter(logger)).isNotNull()
    }

    @Test
    fun `dockerLaunchCleaner and koncertoDockerLifecycle create instances`() {
        val deployer = beans.targetProjectDeployer(logger)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scheduler = beans.orphanedContainerCleanupScheduler(deployer, scope, logger)
        try {
            assertThat(beans.dockerLaunchCleaner(logger)).isInstanceOf(DockerLaunchCleaner::class)
            assertThat(
                beans.koncertoDockerLifecycle(
                    beans.dockerLaunchCleaner(logger),
                    deployer,
                    scheduler,
                    logger
                )
            ).isInstanceOf(KoncertoDockerLifecycle::class)
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `orphanedContainerCleanupScheduler starts and stops`() {
        val deployer = beans.targetProjectDeployer(logger)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scheduler = beans.orphanedContainerCleanupScheduler(deployer, scope, logger)
        try {
            assertThat(scheduler).isInstanceOf(OrphanedContainerCleanupScheduler::class)
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `metricsRepository creates parent directories`() {
        val tempDir = Files.createTempDirectory("metrics-db-test")
        try {
            val dbPath = tempDir.resolve("nested/metrics.db").toString()
            assertThat(beans.metricsRepository(dbPath)).isNotNull()
            assertThat(Files.exists(tempDir.resolve("nested"))).isTrue()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `demoRecordingService creates service when storage and reporter configured`() {
        val demoConfig = DemoConfig(
            enabled = true,
            r2 = DemoConfig.R2Config(
                endpoint = "https://r2.example.com",
                accessKey = "key",
                secretKey = "secret",
                bucketName = "bucket",
                publicUrlBase = "https://cdn.example.com"
            ),
            ai = DemoConfig.AiConfig(model = "free")
        )
        val service = beans.demoRecordingService(
            demoConfig = demoConfig,
            demoTaskRepository = beans.demoTaskRepository(":memory:"),
            recorderFactory = beans.recorderFactory(listOf(beans.playwrightRecorder())),
            demoStorage = beans.demoStorage(demoConfig),
            demoReporter = beans.demoReporter(
                {
                    com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                        com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                        "p"
                    )
                },
                ServiceConfig(
                    projects = mapOf(
                        "test" to ProjectConfig(
                            tracker = TrackerConfig("linear", "x", "k", "p"),
                            workspace = WorkspaceConfig("/tmp"),
                            agent = AgentProjectConfig(kind = "opencode")
                        )
                    )
                )
            ),
            demoReportGenerator = beans.demoReportGenerator(),
            demoMetricsRecorder = beans.demoMetricsRecorder(),
            demoAuditLogger = beans.demoAuditLogger(),
            aiTimelineGenerator = beans.aiTimelineGenerator(demoConfig)
        )
        assertThat(service).isNotNull()
    }

    @Test
    fun `demoEventListener creates listener when recording service exists`() {
        val demoConfig = DemoConfig(enabled = true)
        val service = com.flexsentlabs.koncerto.demo.service.DemoRecordingService(
            demoConfig,
            beans.demoTaskRepository(":memory:"),
            beans.recorderFactory(emptyList()),
            beans.demoStorage(DemoConfig(
                r2 = DemoConfig.R2Config(
                    endpoint = "https://r2.example.com",
                    accessKey = "k",
                    secretKey = "s",
                    bucketName = "b",
                    publicUrlBase = "https://cdn.example.com"
                )
            ))!!,
            beans.demoReporter(
                {
                    com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                        com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                        "p"
                    )
                },
                ServiceConfig(
                    projects = mapOf(
                        "test" to ProjectConfig(
                            tracker = TrackerConfig("linear", "x", "k", "p"),
                            workspace = WorkspaceConfig("/tmp"),
                            agent = AgentProjectConfig(kind = "opencode")
                        )
                    )
                )
            )!!,
            beans.demoReportGenerator(),
            beans.demoMetricsRecorder(),
            beans.demoAuditLogger(),
            null
        )
        assertThat(beans.demoEventListener(service, demoConfig)).isNotNull()
    }

    @Test
    fun `demoCleanupScheduler starts when recording service exists`() {
        val demoConfig = DemoConfig(enabled = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = beans.demoStorage(DemoConfig(
            r2 = DemoConfig.R2Config(
                endpoint = "https://r2.example.com",
                accessKey = "k",
                secretKey = "s",
                bucketName = "b",
                publicUrlBase = "https://cdn.example.com"
            )
        ))!!
        val service = com.flexsentlabs.koncerto.demo.service.DemoRecordingService(
            demoConfig,
            beans.demoTaskRepository(":memory:"),
            beans.recorderFactory(emptyList()),
            storage,
            beans.demoReporter(
                {
                    com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                        com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                        "p"
                    )
                },
                ServiceConfig(
                    projects = mapOf(
                        "test" to ProjectConfig(
                            tracker = TrackerConfig("linear", "x", "k", "p"),
                            workspace = WorkspaceConfig("/tmp"),
                            agent = AgentProjectConfig(kind = "opencode")
                        )
                    )
                )
            )!!,
            beans.demoReportGenerator(),
            beans.demoMetricsRecorder(),
            beans.demoAuditLogger(),
            null
        )
        val scheduler = beans.demoCleanupScheduler(service, demoConfig, scope)
        try {
            assertThat(scheduler).isNotNull()
        } finally {
            scheduler?.stop()
        }
    }

    @Test
    fun `aiTimelineGenerator returns null without ai config`() {
        assertThat(beans.aiTimelineGenerator(DemoConfig(enabled = false, ai = null))).isNull()
    }

    @Test
    fun `parseRepoFullName resolves github remote from config`() {
        val method = Beans::class.java.getDeclaredMethod(
            "parseRepoFullName",
            ServiceConfig::class.java
        )
        method.isAccessible = true
        val config = ServiceConfig(gitConfig = GitConfig(enabled = false, remoteUrl = "git@github.com:owner/repo.git"))
        val repo = method.invoke(beans, config) as String?
        assertThat(repo).isEqualTo("owner/repo")
    }

    @Test
    fun `parseRepoFullName resolves https github remote`() {
        val method = Beans::class.java.getDeclaredMethod("parseRepoFullName", ServiceConfig::class.java)
        method.isAccessible = true
        val config = ServiceConfig(
            gitConfig = GitConfig(enabled = false, remoteUrl = "https://github.com/acme/widgets.git")
        )
        val repo = method.invoke(beans, config) as String?
        assertThat(repo).isEqualTo("acme/widgets")
    }

    @Test
    fun `parseRemoteFromWorkspace reads origin from git config`() {
        val wsRoot = Files.createTempDirectory("beans-parse-ws")
        try {
            val gitDir = wsRoot.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(
                gitDir.resolve("config"),
                """
                |[remote "origin"]
                |    url = https://github.com/ws-owner/ws-repo.git
                """.trimMargin()
            )
            val method = Beans::class.java.getDeclaredMethod("parseRemoteFromWorkspace", String::class.java)
            method.isAccessible = true
            val repo = method.invoke(beans, wsRoot.toString()) as String?
            assertThat(repo).isEqualTo("ws-owner/ws-repo")
        } finally {
            wsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseRepoFullName falls back to workspace git config when remote blank`() {
        val wsRoot = Files.createTempDirectory("beans-fallback-ws")
        try {
            val gitDir = wsRoot.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(
                gitDir.resolve("config"),
                """
                |[remote "origin"]
                |    url = git@github.com:fallback/project.git
                """.trimMargin()
            )
            val method = Beans::class.java.getDeclaredMethod("parseRepoFullName", ServiceConfig::class.java)
            method.isAccessible = true
            val config = ServiceConfig(
                gitConfig = GitConfig(enabled = false, remoteUrl = ""),
                projects = mapOf(
                    "test" to ProjectConfig(
                        tracker = TrackerConfig("linear", "x", "k", "p"),
                        workspace = WorkspaceConfig(wsRoot.toString()),
                        agent = AgentProjectConfig(kind = "opencode")
                    )
                )
            )
            val repo = method.invoke(beans, config) as String?
            assertThat(repo).isEqualTo("fallback/project")
        } finally {
            wsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseRemoteFromWorkspace returns null when workspace has no git dir`() {
        val wsRoot = Files.createTempDirectory("beans-no-git")
        try {
            val method = Beans::class.java.getDeclaredMethod("parseRemoteFromWorkspace", String::class.java)
            method.isAccessible = true
            val repo = method.invoke(beans, wsRoot.toString()) as String?
            assertThat(repo).isNull()
        } finally {
            wsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseRemoteFromWorkspace returns null when origin section missing`() {
        val wsRoot = Files.createTempDirectory("beans-no-origin")
        try {
            val gitDir = wsRoot.resolve(".git")
            Files.createDirectories(gitDir)
            Files.writeString(gitDir.resolve("config"), "[core]\n  filemode = true\n")
            val method = Beans::class.java.getDeclaredMethod("parseRemoteFromWorkspace", String::class.java)
            method.isAccessible = true
            val repo = method.invoke(beans, wsRoot.toString()) as String?
            assertThat(repo).isNull()
        } finally {
            wsRoot.toFile().deleteRecursively()
        }
    }

    @Test
    fun `parseRepoFullName returns null when remote blank and no projects`() {
        val method = Beans::class.java.getDeclaredMethod("parseRepoFullName", ServiceConfig::class.java)
        method.isAccessible = true
        val repo = method.invoke(beans, ServiceConfig(gitConfig = GitConfig(enabled = false, remoteUrl = ""))) as String?
        assertThat(repo).isNull()
    }

    @Test
    fun `extractRepoFullNameFromGitConfig returns null when url missing`() {
        val method = Beans::class.java.getDeclaredMethod("extractRepoFullNameFromGitConfig", String::class.java)
        method.isAccessible = true
        val repo = method.invoke(beans, "[remote \"origin\"]\n  fetch = +refs/heads/*:refs/remotes/origin/*\n") as String?
        assertThat(repo).isNull()
    }

    @Test
    fun `demoController creates controller when service exists`() {
        val demoConfig = DemoConfig(
            enabled = true,
            r2 = DemoConfig.R2Config(
                endpoint = "https://r2.example.com",
                accessKey = "key",
                secretKey = "secret",
                bucketName = "bucket",
                publicUrlBase = "https://cdn.example.com"
            )
        )
        val service = beans.demoRecordingService(
            demoConfig = demoConfig,
            demoTaskRepository = beans.demoTaskRepository(":memory:"),
            recorderFactory = beans.recorderFactory(listOf(beans.playwrightRecorder())),
            demoStorage = beans.demoStorage(demoConfig),
            demoReporter = beans.demoReporter(
                {
                    com.flexsentlabs.koncerto.linear.DefaultLinearClient(
                        com.flexsentlabs.koncerto.linear.LinearGraphQLClient("http://x", "k"),
                        "p"
                    )
                },
                ServiceConfig(
                    projects = mapOf(
                        "test" to ProjectConfig(
                            tracker = TrackerConfig("linear", "x", "k", "p"),
                            workspace = WorkspaceConfig("/tmp"),
                            agent = AgentProjectConfig(kind = "opencode")
                        )
                    )
                )
            ),
            demoReportGenerator = beans.demoReportGenerator(),
            demoMetricsRecorder = beans.demoMetricsRecorder(),
            demoAuditLogger = beans.demoAuditLogger(),
            aiTimelineGenerator = beans.aiTimelineGenerator(demoConfig)
        )
        assertThat(beans.demoController(service)).isNotNull()
    }

    private fun createOrchestratorForConfig(config: ServiceConfig): com.flexsentlabs.koncerto.orchestrator.Orchestrator {
        val tempDir = Files.createTempDirectory("orchestrator-config-test")
        val hookExecutor = HookExecutor { _, _ -> }
        val workspaces = WorkspaceManager(tempDir, hookExecutor)
        val agentRuntimeFactory = AgentRuntimeFactory(logger)
        val gitWorkflow = GitWorkflow(config.gitConfig, logger)
        val runtimeStates = config.projects.mapValues { RuntimeState() }
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
        val workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager = { pc ->
            WorkspaceManager(Paths.get(pc.workspace.root), hookExecutor)
        }
        return beans.orchestrator(
            config = config,
            runner = runner,
            cache = WorkflowCache(),
            logger = logger,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            linearClientFactory = linearClientFactory,
            workspaceManagerFactory = workspaceManagerFactory,
            runtimeStates = runtimeStates,
            metricsRepository = SqliteMetricsRepository(":memory:"),
            compositeNotifier = compositeNotifier,
            subtaskOrchestrator = beans.subtaskOrchestrator(DefaultSubtaskRunner(logger, null), gitWorkflow, logger),
            workplanParser = WorkplanParser(logger),
            auditLogger = FileAuditLogger(Files.createTempFile("audit", ".log")),
            demoEventListener = null,
            targetProjectDeployer = beans.targetProjectDeployer(logger)
        )
    }
}
