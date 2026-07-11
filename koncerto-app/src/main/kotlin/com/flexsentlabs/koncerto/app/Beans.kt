package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.agent.AgentHealthChecker
import com.flexsentlabs.koncerto.agent.AgentHealthEndpoint
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.agent.AgentRuntimeFactory
import com.flexsentlabs.koncerto.agent.DefaultAgentHealthChecker
import com.flexsentlabs.koncerto.agent.DefaultAgentRunner
import com.flexsentlabs.koncerto.agent.DefaultSubtaskRunner
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.TokenBucketRateLimiter
import com.flexsentlabs.koncerto.core.audit.AuditLogger
import com.flexsentlabs.koncerto.logging.audit.FileAuditLogger
import com.flexsentlabs.koncerto.core.agent.AgentCircuitBreaker
import com.flexsentlabs.koncerto.core.CircuitBreaker
import com.flexsentlabs.koncerto.core.circuitbreaker.CircuitBreakerRegistry
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.dashboard.admin.ProjectRegistry
import com.flexsentlabs.koncerto.core.errors.DefaultErrorTracker
import com.flexsentlabs.koncerto.core.errors.ErrorTracker
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.ratelimit.DefaultRateLimitMonitor
import com.flexsentlabs.koncerto.core.ratelimit.RateLimitMonitor
import com.flexsentlabs.koncerto.core.ratelimit.RateLimitRegistry
import com.flexsentlabs.koncerto.linear.DefaultLinearClient
import com.flexsentlabs.koncerto.linear.LinearClient
import com.flexsentlabs.koncerto.linear.LinearGraphQLClient
import com.flexsentlabs.koncerto.linear.RateLimitedLinearClient
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StderrSink
import com.flexsentlabs.koncerto.logging.RollingFileSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.Notifier
import com.flexsentlabs.koncerto.notifications.channel.LoggingNotifier
import com.flexsentlabs.koncerto.notifications.channel.SmtpEmailNotifier
import com.flexsentlabs.koncerto.notifications.channel.TelegramNotifier
import com.flexsentlabs.koncerto.notifications.channel.WebhookNotifier

import com.flexsentlabs.koncerto.metrics.MetricsRepository
import com.flexsentlabs.koncerto.metrics.PrometheusMetricsBinder
import com.flexsentlabs.koncerto.metrics.SqliteMetricsRepository
import io.micrometer.core.instrument.binder.MeterBinder
import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.demo.DemoController
import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.integration.DemoEventListener
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.AdbRecorder
import com.flexsentlabs.koncerto.demo.recorder.AsciinemaRecorder
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.FfmpegRecorder
import com.flexsentlabs.koncerto.demo.recorder.PlaywrightRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.recorder.XcrunRecorder
import com.flexsentlabs.koncerto.demo.report.AiTimelineGenerator
import com.flexsentlabs.koncerto.demo.report.DemoReporter
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.report.LinearReportPublisher
import com.flexsentlabs.koncerto.demo.repository.DemoTaskRepository
import com.flexsentlabs.koncerto.demo.repository.SqliteDemoTaskRepository
import com.flexsentlabs.koncerto.demo.service.DemoCleanupScheduler
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import com.flexsentlabs.koncerto.demo.storage.R2DemoStorage
import com.flexsentlabs.koncerto.agent.ModelRetryHandler
import com.flexsentlabs.koncerto.orchestrator.AutoReviewOrchestrator
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.orchestrator.RuntimeState
import com.flexsentlabs.koncerto.orchestrator.SubtaskFrontier
import com.flexsentlabs.koncerto.orchestrator.SubtaskOrchestrator
import com.flexsentlabs.koncerto.orchestrator.WorkplanParser
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import com.flexsentlabs.koncerto.deploy.ContainerLifecycleManager
import com.flexsentlabs.koncerto.deploy.DemoFailureReporter
import com.flexsentlabs.koncerto.deploy.DockerConfigDetector
import com.flexsentlabs.koncerto.deploy.DockerfileGenerator
import com.flexsentlabs.koncerto.deploy.FrameworkDetector
import com.flexsentlabs.koncerto.deploy.GitHubPRQueryImpl
import com.flexsentlabs.koncerto.deploy.DockerLaunchCleaner
import com.flexsentlabs.koncerto.deploy.OrphanedContainerCleanupScheduler
import com.flexsentlabs.koncerto.deploy.TargetProjectDeployer
import com.flexsentlabs.koncerto.orchestrator.DemoScenarioGenerator
import com.flexsentlabs.koncerto.orchestrator.ScenarioCoverageClassifier
import com.flexsentlabs.koncerto.orchestrator.TestResourceRequirementDetector
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import com.flexsentlabs.koncerto.workflow.WorkflowLoader
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration(proxyBeanMethods = false)
class Beans {

    @Bean
    fun logger(@Value("\${koncerto.logs-root:}") logsRoot: String?): StructuredLogger {
        val sinks = mutableListOf<LogSink>(StderrSink())
        if (!logsRoot.isNullOrBlank()) {
            val dir = Paths.get(logsRoot)
            Files.createDirectories(dir)
            sinks += RollingFileSink(dir, baseName = "koncerto", retentionDays = 7)
        }
        return StructuredLogger(sinks)
    }

    @Bean
    fun workflowCache(): WorkflowCache = WorkflowCache()

    @Bean
    fun projectRegistry(): ProjectRegistry = ProjectRegistry()

    @Bean
    fun configService(
        @Value("\${koncerto.workflow-path}") workflowPath: String
    ): ConfigService = ConfigService(workflowPath)

    @Bean
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Bean
    @Primary
    fun serviceConfig(
        @Value("\${koncerto.workflow-path}") workflowPath: String,
        workflowCache: WorkflowCache,
        logger: StructuredLogger
    ): ServiceConfig {
        val path = Paths.get(workflowPath)
        val def = WorkflowLoader.loadFromPath(path)
        workflowCache.set(def)
        val parent = path.parent
        if (parent != null) workflowCache.setWorkflowDir(parent)
        val workflowFileDir = parent?.toString() ?: "."
        @Suppress("UNCHECKED_CAST")
        val configMap = def.config
        val config = ServiceConfig.fromMap(configMap, workflowFileDir)
        config.deprecationWarnings.forEach { msg ->
            logger.warn("config_deprecation", mapOf("message" to msg))
        }
        return config
    }

    @Bean
    fun shellHookExecutor(config: ServiceConfig, logger: StructuredLogger) =
        com.flexsentlabs.koncerto.workspace.ShellHookExecutor(config.hooks.timeoutMs, logger)

    @Bean
    fun workspaceManager(
        config: ServiceConfig,
        shellHookExecutor: com.flexsentlabs.koncerto.workspace.ShellHookExecutor
    ): WorkspaceManager {
        val firstProject = config.projects.values.firstOrNull()
            ?: throw IllegalStateException("At least one project must be configured")
        return WorkspaceManager(Paths.get(firstProject.workspace.root), shellHookExecutor)
    }

    @Bean
    fun workspaceManagerFactory(
        shellHookExecutor: com.flexsentlabs.koncerto.workspace.ShellHookExecutor
    ): (ProjectConfig) -> WorkspaceManager = { pc ->
        WorkspaceManager(Paths.get(pc.workspace.root), shellHookExecutor)
    }

    @Bean
    fun linearClientFactory(
        logger: StructuredLogger,
        scope: CoroutineScope
    ): (ProjectConfig) -> LinearClient = { pc ->
        val rateLimitProvider = pc.rateLimits?.linear?.let { rlConfig ->
            RateLimitRegistry.getOrCreate("linear-${pc.tracker.projectSlug ?: "default"}", rlConfig, scope)
        }
        val providerCbKey = "linear-${pc.tracker.projectSlug ?: "default"}"
        val providerCb = CircuitBreakerRegistry.getOrCreate(providerCbKey,
            com.flexsentlabs.koncerto.core.circuitbreaker.CircuitBreakerConfig(
                failureThreshold = pc.circuitBreaker?.failureThreshold ?: 5,
                resetTimeoutMs = pc.circuitBreaker?.resetTimeoutMs ?: 30_000
            )
        )
        val graphql = LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey, rateLimitProvider = rateLimitProvider, circuitBreaker = providerCb)
        val slug = pc.tracker.projectSlug
            ?: throw IllegalStateException("missing_tracker_project_slug")
        val base = DefaultLinearClient(graphql, slug)
        val rateLimiter = pc.rateLimiter?.let {
            TokenBucketRateLimiter(
                maxTokens = it.maxBurst,
                refillIntervalMs = 1000,
                refillCount = it.requestsPerSecond
            )
        }
        val circuitBreaker = pc.circuitBreaker?.let {
            CircuitBreaker(it.failureThreshold, it.resetTimeoutMs)
        }
        if (rateLimiter != null || circuitBreaker != null) {
            RateLimitedLinearClient(base, rateLimiter, circuitBreaker, logger)
        } else base
    }

    @Bean
    fun agentRuntimeFactory(logger: StructuredLogger): AgentRuntimeFactory = AgentRuntimeFactory(logger)

    @Bean
    fun freeModelCycler(logger: StructuredLogger): FreeModelCycler = FreeModelCycler.createDefault(logger)

    @Bean
    fun modelRetryHandler(
        config: ServiceConfig,
        linearClientFactory: (ProjectConfig) -> LinearClient,
        compositeNotifier: CompositeNotifier,
        logger: StructuredLogger
    ): ModelRetryHandler {
        val firstProject = config.projects.values.firstOrNull()
            ?: throw IllegalStateException("No project config found")
        val linearClient = linearClientFactory(firstProject)
        return ModelRetryHandler(
            cycler = freeModelCycler(logger),
            projectConfig = firstProject,
            linearClient = linearClient,
            notifier = compositeNotifier,
            logger = logger
        )
    }

    @Bean
    fun gitWorkflow(config: ServiceConfig, logger: StructuredLogger): GitWorkflow =
        GitWorkflow(config.gitConfig, logger)

    @Bean
    fun subtaskRunner(
        logger: StructuredLogger,
        runtimeFactory: AgentRuntimeFactory? = null
    ): SubtaskRunner = DefaultSubtaskRunner(logger, runtimeFactory)

    @Bean
    fun subtaskFrontier(): SubtaskFrontier = SubtaskFrontier()

    @Bean
    fun workplanParser(
        logger: StructuredLogger
    ): WorkplanParser = WorkplanParser(logger)

    @Bean
    fun subtaskOrchestrator(
        subtaskRunner: SubtaskRunner,
        gitWorkflow: GitWorkflow,
        logger: StructuredLogger
    ): SubtaskOrchestrator = SubtaskOrchestrator(subtaskRunner, gitWorkflow, logger)

    @Bean
    fun logNotifier(logger: StructuredLogger): LoggingNotifier = LoggingNotifier(logger)

    @Bean
    fun compositeNotifier(
        config: ServiceConfig,
        logNotifier: LoggingNotifier
    ): CompositeNotifier {
        val notifiers = mutableListOf<Notifier>(logNotifier)
        val nc = config.projects.values.firstOrNull()?.notifications ?: return CompositeNotifier(notifiers)
        val webhook = nc.webhook
        if (webhook != null) {
            notifiers.add(WebhookNotifier(webhook.url, webhook.headers))
        }
        val telegram = nc.telegram
        if (telegram != null) {
            notifiers.add(TelegramNotifier(telegram.botToken, telegram.chatId))
        }
        val email = nc.email
        if (email != null) {
            notifiers.add(SmtpEmailNotifier(
                email.smtpHost, email.smtpPort,
                email.username, email.password,
                email.from, email.to
            ))
        }
        return CompositeNotifier(notifiers)
    }

    @Bean
    fun agentHealthChecker(): AgentHealthChecker = DefaultAgentHealthChecker()

    @Bean
    fun agentHealthEndpoint(healthChecker: AgentHealthChecker): AgentHealthEndpoint =
        AgentHealthEndpoint(healthChecker)

    @Bean
    fun agentCircuitBreaker(): AgentCircuitBreaker = AgentCircuitBreaker()

    @Bean
    fun rateLimitMonitor(): RateLimitMonitor = DefaultRateLimitMonitor()

    @Bean
    fun errorTracker(): ErrorTracker = DefaultErrorTracker()

    @Bean
    fun errorClassifier(): PatternErrorClassifier = PatternErrorClassifier()

    @Bean
    fun agentRunner(
        config: ServiceConfig,
        workspaces: WorkspaceManager,
        logger: StructuredLogger,
        agentRuntimeFactory: AgentRuntimeFactory,
        gitWorkflow: GitWorkflow,
        runtimeStates: Map<String, RuntimeState>,
        circuitBreaker: AgentCircuitBreaker,
        errorTracker: ErrorTracker,
        healthChecker: AgentHealthChecker,
        errorClassifier: PatternErrorClassifier,
        freeModelCycler: FreeModelCycler,
        modelRetryHandler: ModelRetryHandler
    ): AgentRunner {
        val firstProject = config.projects.values.firstOrNull()
        val heartbeatInterval = firstProject?.agent?.heartbeatIntervalMs ?: 30_000L
        val dockerConfig = firstProject?.agent?.docker
        val maxConcurrentAgents = firstProject?.agent?.maxConcurrentAgents ?: 2
        return DefaultAgentRunner(
            config, workspaces, logger, agentRuntimeFactory, gitWorkflow,
            onAgentOutputSuspend = { issueId, line ->
                runtimeStates.values.firstOrNull {
                    it.running.containsKey(issueId) || it.claimed.contains(issueId)
                }?.appendOutput(issueId, line)
            },
            freeModelCycler = freeModelCycler,
            modelRetryHandler = modelRetryHandler,
            heartbeatIntervalMs = heartbeatInterval,
            circuitBreaker = circuitBreaker,
            errorTracker = errorTracker,
            healthChecker = healthChecker,
            errorClassifier = errorClassifier,
            maxRetries = 3,
            retryDelayMs = 5_000L,
            dockerConfig = dockerConfig,
            maxConcurrentAgents = maxConcurrentAgents
        )
    }

    @Bean
    fun metricsRepository(@Value("\${koncerto.db.path:${'$'}{user.home}/.koncerto/metrics.db}") dbPath: String): MetricsRepository {
        val dir = java.nio.file.Paths.get(dbPath).parent
        if (dir != null) java.nio.file.Files.createDirectories(dir)
        return SqliteMetricsRepository(dbPath)
    }

    @Bean
    fun prometheusMetricsBinder(metricsRepository: MetricsRepository): MeterBinder =
        PrometheusMetricsBinder(metricsRepository as SqliteMetricsRepository)

    @Bean
    fun runtimeStates(
        config: ServiceConfig
    ): Map<String, RuntimeState> = config.projects.mapValues { (_, projectConfig) ->
        RuntimeState().also {
            it.pollIntervalMs = config.pollIntervalMs
            it.maxConcurrentAgents = projectConfig.agent.maxConcurrentAgents
        }
    }

    @Bean
    fun auditLogger(@Value("\${koncerto.audit.path:${'$'}{user.home}/.koncerto/audit.log}") path: String): AuditLogger =
        FileAuditLogger(java.nio.file.Paths.get(path))

    @Bean
    fun orchestrator(
        config: ServiceConfig,
        runner: AgentRunner,
        cache: WorkflowCache,
        logger: StructuredLogger,
        scope: CoroutineScope,
        linearClientFactory: (ProjectConfig) -> LinearClient,
        workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager,
        runtimeStates: Map<String, RuntimeState>,
        metricsRepository: MetricsRepository?,
        compositeNotifier: CompositeNotifier?,
        subtaskOrchestrator: SubtaskOrchestrator?,
        workplanParser: WorkplanParser?,
        auditLogger: AuditLogger?,
        demoEventListener: DemoEventListener?,
        targetProjectDeployer: TargetProjectDeployer
    ): Orchestrator {
        val notifier = compositeNotifier ?: CompositeNotifier(emptyList())
        return Orchestrator(
            config = config,
            linearClientFactory = linearClientFactory,
            workspaceManagerFactory = workspaceManagerFactory,
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = scope,
            runtimeStates = runtimeStates,
            metricsRepository = metricsRepository,
            notifier = compositeNotifier,
            subtaskOrchestrator = subtaskOrchestrator,
            workplanParser = workplanParser,
            auditLogger = auditLogger,
            autoReviewOrchestratorFactory = if (config.projects.values.any { it.agent.stages.containsKey("in review") }) {
                { pc, state ->
                    val slug = pc.tracker.projectSlug ?: "default"
                    AutoReviewOrchestrator(
                        agentRunner = runner,
                        workspaceManager = workspaceManagerFactory(pc),
                        linearClient = linearClientFactory(pc),
                        projectConfig = pc,
                        projectSlug = slug,
                        runtimeState = state,
                        notifier = compositeNotifier,
                        logger = logger,
                        workflowCache = cache,
                        onReviewPassed = demoEventListener?.let { listener ->
                            { issue, targetUrl -> listener.onReviewPassed(issue.id, issue.identifier, slug, targetUrl) }
                        },
                        targetProjectDeployer = targetProjectDeployer,
                        deployRepoFullName = parseRepoFullName(config),
                        demoFailureReporter = DemoFailureReporter(logger),
                        demoScenarioGenerator = DemoScenarioGenerator("opencode", logger, workflowCache = cache),
                        scenarioCoverageClassifier = ScenarioCoverageClassifier("opencode", logger)
                    )
                }
            } else null,
            testResourceDetector = TestResourceRequirementDetector("opencode", logger)
        )
    }

    @Bean
    fun demoConfig(serviceConfig: ServiceConfig): DemoConfig {
        val dr = serviceConfig.demoRecording
        return DemoConfig(
            enabled = dr.enabled,
            targetUrl = dr.targetUrl,
            tempDir = "/tmp/koncerto-demo",
            maxRetries = dr.retry.maxAttempts,
            retryDelayMs = 5_000L,
            preflightTimeoutMs = 10_000L,
            retentionDays = 90,
            maxRecordingsPerSpace = 100,
            defaultPlatform = dr.platform.web,
            r2 = dr.storage?.let { s ->
                DemoConfig.R2Config(
                    endpoint = s.r2Endpoint,
                    accessKey = s.r2AccessKey,
                    secretKey = s.r2SecretKey,
                    bucketName = s.r2Bucket,
                    publicUrlBase = s.publicUrlBase,
                    presignedUrlTtlSeconds = s.presignedUrlTtl,
                    region = s.region
                )
            },
            ai = DemoConfig.AiConfig(
                model = dr.ai.model,
                timelineEnabled = dr.ai.timeline,
                reproStepsEnabled = dr.ai.reproSteps
            ),
            cleanupIntervalHours = dr.cleanupIntervalHours
        )
    }

    @Bean
    fun playwrightRecorder(): DemoRecorder = PlaywrightRecorder()

    @Bean
    fun asciinemaRecorder(): DemoRecorder = AsciinemaRecorder()

    @Bean
    fun adbRecorder(): DemoRecorder = AdbRecorder()

    @Bean
    fun xcrunRecorder(): DemoRecorder = XcrunRecorder()

    @Bean
    fun ffmpegRecorder(): DemoRecorder = FfmpegRecorder()

    @Bean
    fun recorderFactory(recorders: List<DemoRecorder>): RecorderFactory = RecorderFactory(recorders)

    @Bean
    fun demoTaskRepository(@Value("\${koncerto.db.path:${'$'}{user.home}/.koncerto/metrics.db}") dbPath: String): DemoTaskRepository {
        val dir = java.nio.file.Paths.get(dbPath).parent
        if (dir != null) java.nio.file.Files.createDirectories(dir)
        return SqliteDemoTaskRepository(dbPath)
    }

    @Bean
    fun demoStorage(config: DemoConfig): DemoStorage? {
        val r2 = config.r2 ?: return null
        return R2DemoStorage(
            endpoint = r2.endpoint,
            accessKey = r2.accessKey,
            secretKey = r2.secretKey,
            bucketName = r2.bucketName,
            publicUrlBase = r2.publicUrlBase,
            presignedUrlTtlSeconds = r2.presignedUrlTtlSeconds,
            region = r2.region
        )
    }

    @Bean
    fun demoReporter(linearClientFactory: (ProjectConfig) -> LinearClient, config: ServiceConfig): DemoReporter? {
        val project = config.projects.values.firstOrNull() ?: return null
        val linearClient = linearClientFactory(project)
        return LinearReportPublisher(linearClient)
    }

    @Bean
    fun demoReportGenerator(): DemoReportGenerator = DemoReportGenerator()

    @Bean
    fun demoMetricsRecorder(): DemoMetricsRecorder = DemoMetricsRecorder()

    @Bean
    fun demoAuditLogger(): DemoAuditLogger = DemoAuditLogger()

    @Bean
    fun aiTimelineGenerator(demoConfig: DemoConfig): AiTimelineGenerator? {
        val ai = demoConfig.ai ?: return null
        return AiTimelineGenerator(
            apiEndpoint = ai.endpoint,
            apiKey = ai.apiKey,
            model = ai.model
        )
    }

    @Bean
    fun demoRecordingService(
        demoConfig: DemoConfig,
        demoTaskRepository: DemoTaskRepository,
        recorderFactory: RecorderFactory,
        demoStorage: DemoStorage?,
        demoReporter: DemoReporter?,
        demoReportGenerator: DemoReportGenerator,
        demoMetricsRecorder: DemoMetricsRecorder,
        demoAuditLogger: DemoAuditLogger,
        aiTimelineGenerator: AiTimelineGenerator?
    ): DemoRecordingService? {
        if (demoStorage == null || demoReporter == null) return null
        return DemoRecordingService(
            demoConfig, demoTaskRepository, recorderFactory, demoStorage, demoReporter,
            demoReportGenerator, demoMetricsRecorder, demoAuditLogger, aiTimelineGenerator
        )
    }

    @Bean
    fun demoController(demoRecordingService: DemoRecordingService?): DemoController? {
        if (demoRecordingService == null) return null
        return DemoController(demoRecordingService)
    }

    @Bean
    fun demoEventListener(
        demoRecordingService: DemoRecordingService?,
        demoConfig: DemoConfig
    ): DemoEventListener? {
        if (demoRecordingService == null) return null
        return DemoEventListener(demoRecordingService, enabled = demoConfig.enabled)
    }

    @Bean
    fun targetProjectDeployer(logger: StructuredLogger): TargetProjectDeployer {
        val configDetector = DockerConfigDetector()
        val frameworkDetector = FrameworkDetector()
        val dockerfileGenerator = DockerfileGenerator()
        val containerManager = ContainerLifecycleManager(logger)
        return TargetProjectDeployer(
            configDetector, frameworkDetector, dockerfileGenerator,
            containerManager, logger
        )
    }

    @Bean
    fun demoFailureReporter(logger: StructuredLogger): DemoFailureReporter =
        DemoFailureReporter(logger)

    private fun parseRepoFullName(config: ServiceConfig): String? {
        val firstProject = config.projects.values.firstOrNull()
        val remoteUrl = firstProject?.gitRemoteUrl?.takeIf { it.isNotBlank() }
            ?: config.gitConfig.remoteUrl
        if (remoteUrl.isBlank()) {
            return firstProject?.let { parseRemoteFromWorkspace(it.workspace.root) }
        }
        val match = Regex("""github\.com[:/]([^/]+/[^/]+?)(?:\.git)?$""").find(remoteUrl)
        return match?.groupValues?.get(1)
    }

    private fun parseRemoteFromWorkspace(root: String): String? {
        val gitDir = Paths.get(root).resolve(".git")
        if (!Files.exists(gitDir)) return null
        return try {
            val configFile = gitDir.resolve("config")
            if (!Files.exists(configFile)) return null
            val content = Files.readString(configFile)
            extractRepoFullNameFromGitConfig(content)
        } catch (_: Exception) { null }
    }

    private fun extractRepoFullNameFromGitConfig(content: String): String? {
        val lines = content.lineSequence().map { it.trim() }.toList()
        val originIndex = lines.indexOf("[remote \"origin\"]")
        if (originIndex < 0) return null
        val remoteUrl = lines.drop(originIndex + 1)
            .firstOrNull { it.startsWith("url =") }
            ?.removePrefix("url =")
            ?.trim()
            .orEmpty()
        val match = Regex("""github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?$""").find(remoteUrl)
        return match?.groupValues?.get(1)
    }

    @Bean
    fun demoCleanupScheduler(
        demoRecordingService: DemoRecordingService?,
        demoConfig: DemoConfig,
        scope: CoroutineScope
    ): DemoCleanupScheduler? {
        if (demoRecordingService == null) return null
        val scheduler = DemoCleanupScheduler(
            recordingService = demoRecordingService,
            scope = scope,
            intervalHours = demoConfig.cleanupIntervalHours
        )
        scheduler.start()
        return scheduler
    }

    @Bean
    fun dockerLaunchCleaner(logger: StructuredLogger): DockerLaunchCleaner =
        DockerLaunchCleaner(logger)

    @Bean
    fun orphanedContainerCleanupScheduler(
        targetProjectDeployer: TargetProjectDeployer,
        scope: CoroutineScope,
        logger: StructuredLogger
    ): OrphanedContainerCleanupScheduler {
        val scheduler = OrphanedContainerCleanupScheduler(
            deployer = targetProjectDeployer,
            scope = scope,
            logger = logger,
            intervalMinutes = 5
        )
        scheduler.start()
        return scheduler
    }

    @Bean
    fun koncertoDockerLifecycle(
        dockerLaunchCleaner: DockerLaunchCleaner,
        targetProjectDeployer: TargetProjectDeployer,
        orphanedContainerCleanupScheduler: OrphanedContainerCleanupScheduler,
        logger: StructuredLogger
    ): KoncertoDockerLifecycle = KoncertoDockerLifecycle(
        launchCleaner = dockerLaunchCleaner,
        deployer = targetProjectDeployer,
        orphanScheduler = orphanedContainerCleanupScheduler,
        logger = logger
    )
}
