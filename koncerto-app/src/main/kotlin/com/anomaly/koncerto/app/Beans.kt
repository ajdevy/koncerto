package com.anomaly.koncerto.app

import com.anomaly.koncerto.agent.AgentRunner
import com.anomaly.koncerto.agent.AgentRuntimeFactory
import com.anomaly.koncerto.agent.DefaultAgentRunner
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.linear.DefaultLinearClient
import com.anomaly.koncerto.linear.LinearClient
import com.anomaly.koncerto.linear.LinearGraphQLClient
import com.anomaly.koncerto.logging.FileSink
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StderrSink
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.orchestrator.RuntimeState
import com.anomaly.koncerto.workspace.ShellHookExecutor
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
    fun workspaceManager(config: ServiceConfig, logger: StructuredLogger): WorkspaceManager {
        val executor = ShellHookExecutor(config.hooks.timeoutMs, logger)
        return WorkspaceManager(config.workspaceRoot, executor)
    }

    @Bean
    fun linearClient(config: ServiceConfig): LinearClient {
        val graphql = LinearGraphQLClient(config.trackerEndpoint, config.trackerApiKey)
        val slug = config.trackerProjectSlug ?: throw IllegalStateException("missing_tracker_project_slug")
        return DefaultLinearClient(graphql, slug)
    }

    @Bean
    fun agentRuntimeFactory(logger: StructuredLogger): AgentRuntimeFactory = AgentRuntimeFactory(logger)

    @Bean
    fun agentRunner(
        config: ServiceConfig,
        workspaces: WorkspaceManager,
        logger: StructuredLogger,
        agentRuntimeFactory: AgentRuntimeFactory
    ): AgentRunner = DefaultAgentRunner(config, workspaces, logger, agentRuntimeFactory)

    @Bean
    fun runtimeState(config: ServiceConfig): RuntimeState = RuntimeState().also {
        it.pollIntervalMs = config.pollIntervalMs
        it.maxConcurrentAgents = config.maxConcurrentAgents
        it.workspaceRoot = config.workspaceRoot
    }

    @Bean
    fun orchestrator(
        config: ServiceConfig,
        state: RuntimeState,
        linear: LinearClient,
        workspaces: WorkspaceManager,
        runner: AgentRunner,
        cache: WorkflowCache,
        logger: StructuredLogger,
        scope: CoroutineScope
    ): Orchestrator = Orchestrator(
        config, state, linear, workspaces, runner, cache, logger,
        config.trackerProjectSlug ?: "unknown"
    )
}
