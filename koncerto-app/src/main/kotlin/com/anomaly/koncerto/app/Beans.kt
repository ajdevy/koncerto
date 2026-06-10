package com.anomaly.koncerto.app

import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.AgentRuntimeFactory
import com.anomaly.koncerto.agent.DefaultAgentRunner
import com.anomaly.koncerto.core.CircuitBreaker
import com.anomaly.koncerto.core.TokenBucketRateLimiter
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
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
import com.anomaly.koncerto.metrics.SqliteMetricsRepository
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.orchestrator.RuntimeState
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
        logger: StructuredLogger
    ): (ProjectConfig) -> LinearClient = { pc ->
        val graphql = LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey)
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
    fun gitWorkflow(config: ServiceConfig, logger: StructuredLogger): GitWorkflow =
        GitWorkflow(config.gitConfig, logger)

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
    fun agentRunner(
        config: ServiceConfig,
        workspaces: WorkspaceManager,
        logger: StructuredLogger,
        agentRuntimeFactory: AgentRuntimeFactory,
        gitWorkflow: GitWorkflow,
        runtimeStates: Map<String, RuntimeState>
    ): AgentRunner {
        val firstProject = config.projects.values.firstOrNull()
        val heartbeatInterval = firstProject?.agent?.heartbeatIntervalMs ?: 30_000L
        return DefaultAgentRunner(
            config, workspaces, logger, agentRuntimeFactory, gitWorkflow,
            onAgentOutput = { issueId, line ->
                runtimeStates.values.firstOrNull {
                    it.running.containsKey(issueId) || it.claimed.contains(issueId)
                }?.appendOutput(issueId, line)
            },
            heartbeatIntervalMs = heartbeatInterval
        )
    }

    @Bean
    fun metricsRepository(@Value("\${koncerto.db.path:${'$'}{user.home}/.koncerto/metrics.db}") dbPath: String): MetricsRepository {
        val dir = java.nio.file.Paths.get(dbPath).parent
        if (dir != null) java.nio.file.Files.createDirectories(dir)
        return SqliteMetricsRepository(dbPath)
    }

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
    fun orchestrator(
        config: ServiceConfig,
        runner: AgentRunner,
        cache: WorkflowCache,
        logger: StructuredLogger,
        scope: CoroutineScope,
        linearClientFactory: (ProjectConfig) -> LinearClient,
        workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager,
        runtimeStates: Map<String, RuntimeState>,
        metricsRepository: MetricsRepository?
    ): Orchestrator = Orchestrator(
        config = config,
        linearClientFactory = linearClientFactory,
        workspaceManagerFactory = workspaceManagerFactory,
        agentRunner = runner,
        workflowCache = cache,
        logger = logger,
        scope = scope,
        runtimeStates = runtimeStates,
        metricsRepository = metricsRepository
    )
}
