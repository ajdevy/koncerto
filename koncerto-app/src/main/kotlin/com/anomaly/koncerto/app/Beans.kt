package com.anomaly.koncerto.app

import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.AgentRuntimeFactory
import com.anomaly.koncerto.agent.DefaultAgentRunner
import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.logging.FileSink
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StderrSink
import com.anomaly.koncerto.logging.StructuredLogger
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

@Configuration
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
    fun serviceConfig(
        @Value("\${koncerto.workflow-path}") workflowPath: String,
        workflowCache: WorkflowCache
    ): ServiceConfig {
        val path = Paths.get(workflowPath)
        val def = WorkflowLoader.loadFromPath(path)
        workflowCache.set(def)
        val workflowFileDir = path.parent?.toString() ?: "."
        @Suppress("UNCHECKED_CAST")
        val configMap = def.config
        return ServiceConfig.fromMap(configMap, workflowFileDir)
    }

    @Bean
    fun workspaceManagerFactory(
        config: ServiceConfig,
        logger: StructuredLogger
    ): (ProjectConfig) -> WorkspaceManager = { pc ->
        val executor = com.anomaly.koncerto.workspace.ShellHookExecutor(config.hooks.timeoutMs, logger)
        WorkspaceManager(Paths.get(pc.workspace.root), executor)
    }

    @Bean
    fun linearClientFactory(): (ProjectConfig) -> LinearClient = { pc ->
        val graphql = com.anomaly.koncerto.linear.LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey)
        val slug = pc.tracker.projectSlug
            ?: throw IllegalStateException("missing_tracker_project_slug")
        com.anomaly.koncerto.linear.DefaultLinearClient(graphql, slug)
    }

    @Bean
    fun agentRuntimeFactory(logger: StructuredLogger): AgentRuntimeFactory = AgentRuntimeFactory(logger)

    @Bean
    fun gitWorkflow(config: ServiceConfig, logger: StructuredLogger): GitWorkflow =
        GitWorkflow(config.gitConfig, logger)

    @Bean
    fun agentRunner(
        config: ServiceConfig,
        workspaces: WorkspaceManager,
        logger: StructuredLogger,
        agentRuntimeFactory: AgentRuntimeFactory,
        gitWorkflow: GitWorkflow,
        runtimeStates: Map<String, RuntimeState>
    ): AgentRunner = DefaultAgentRunner(
        config, workspaces, logger, agentRuntimeFactory, gitWorkflow,
        onAgentOutput = { issueId, line ->
            runtimeStates.values.firstOrNull {
                it.running.containsKey(issueId) || it.claimed.contains(issueId)
            }?.appendOutput(issueId, line)
        }
    )

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
