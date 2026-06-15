package com.anomaly.koncerto.app

import com.anomaly.koncerto.agent.AgentHealthChecker
import com.anomaly.koncerto.agent.AgentHealthEndpoint
import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.AgentRuntimeFactory
import com.anomaly.koncerto.agent.DefaultAgentHealthChecker
import com.anomaly.koncerto.agent.DefaultAgentRunner
import com.anomaly.koncerto.agent.DefaultSubtaskRunner
import com.anomaly.koncerto.agent.SubtaskRunner
import com.anomaly.koncerto.core.TokenBucketRateLimiter
import com.anomaly.koncerto.core.audit.AuditLogger
import com.anomaly.koncerto.logging.audit.FileAuditLogger
import com.anomaly.koncerto.core.agent.AgentCircuitBreaker
import com.anomaly.koncerto.core.CircuitBreaker
import com.anomaly.koncerto.core.circuitbreaker.CircuitBreakerRegistry
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.dashboard.admin.ProjectRegistry
import com.anomaly.koncerto.core.errors.DefaultErrorTracker
import com.anomaly.koncerto.core.errors.ErrorTracker
import com.anomaly.koncerto.core.errors.PatternErrorClassifier
import com.anomaly.koncerto.core.ratelimit.DefaultRateLimitMonitor
import com.anomaly.koncerto.core.ratelimit.RateLimitMonitor
import com.anomaly.koncerto.core.ratelimit.RateLimitRegistry
import com.anomaly.koncerto.linear.DefaultLinearClient
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.linear.LinearGraphQLClient
import com.anomaly.koncerto.linear.RateLimitedLinearClient
import com.anomaly.koncerto.logging.FileSink
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StderrSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.notifications.CompositeNotifier
import com.anomaly.koncerto.notifications.Notifier
import com.anomaly.koncerto.notifications.channel.LoggingNotifier
import com.anomaly.koncerto.notifications.channel.SmtpEmailNotifier
import com.anomaly.koncerto.notifications.channel.TelegramNotifier
import com.anomaly.koncerto.notifications.channel.WebhookNotifier

import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.metrics.PrometheusMetricsBinder
import com.anomaly.koncerto.metrics.SqliteMetricsRepository
import io.micrometer.core.instrument.binder.MeterBinder
import com.anomaly.koncerto.agent.FreeModelCycler
import com.anomaly.koncerto.agent.ModelRetryHandler
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.orchestrator.RuntimeState
import com.anomaly.koncerto.orchestrator.SubtaskFrontier
import com.anomaly.koncerto.orchestrator.SubtaskOrchestrator
import com.anomaly.koncerto.orchestrator.WorkplanParser
import com.anomaly.koncerto.workspace.GitWorkflow
import com.anomaly.koncerto.workspace.WorkspaceManager
import com.anomaly.koncerto.workflow.WorkflowCache
import com.anomaly.koncerto.workflow.WorkflowLoader
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
            sinks += FileSink(dir.resolve("koncerto.log"))
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
        val workflowFileDir = path.parent?.toString() ?: "."
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
        com.anomaly.koncerto.workspace.ShellHookExecutor(config.hooks.timeoutMs, logger)

    @Bean
    fun workspaceManager(
        config: ServiceConfig,
        shellHookExecutor: com.anomaly.koncerto.workspace.ShellHookExecutor
    ): WorkspaceManager {
        val firstProject = config.projects.values.first()
        return WorkspaceManager(Paths.get(firstProject.workspace.root), shellHookExecutor)
    }

    @Bean
    fun workspaceManagerFactory(
        shellHookExecutor: com.anomaly.koncerto.workspace.ShellHookExecutor
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
            com.anomaly.koncerto.core.circuitbreaker.CircuitBreakerConfig(
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
        auditLogger: AuditLogger?
    ): Orchestrator = Orchestrator(
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
        auditLogger = auditLogger
    )
}
