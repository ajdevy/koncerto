package com.anomaly.koncerto.core.config

import com.anomaly.koncerto.core.result.EmptyResult
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.result.runCatchingResult
import java.nio.file.Path
import java.nio.file.Paths

data class ServiceConfig(
    val trackerKind: String?,
    val trackerEndpoint: String,
    val trackerApiKey: String?,
    val trackerProjectSlug: String?,
    val requiredLabels: List<String>,
    val activeStates: List<String>,
    val terminalStates: List<String>,
    val pollIntervalMs: Long,
    val workspaceRoot: Path,
    val hooks: HooksConfig,
    val maxConcurrentAgents: Int,
    val maxTurns: Int,
    val maxRetryBackoffMs: Long,
    val maxConcurrentAgentsByState: Map<String, Int>,
    val agentKind: String,
    val codexCommand: String,
    val codexApprovalPolicy: Map<String, Any?>?,
    val codexThreadSandbox: String?,
    val codexTurnSandboxPolicy: Map<String, Any?>?,
    val opencodeCommand: String,
    val turnTimeoutMs: Long,
    val readTimeoutMs: Long,
    val stallTimeoutMs: Long
) {
    fun hooksTimeoutMs(): Long = hooks.timeoutMs

    companion object {
        fun fromMap(map: Map<String, Any?>, workflowFileDir: String): ServiceConfig =
            fromMapOrError(map, workflowFileDir).let { result ->
                when (result) {
                    is Result.Success -> result.value
                    is Result.Failure -> throw IllegalStateException("Invalid config", result.error)
                }
            }

        fun fromMapOrError(
            map: Map<String, Any?>,
            workflowFileDir: String
        ): Result<ServiceConfig, IllegalStateException> = runCatchingResult {
            val trackerSection = parseTrackerSection(map["tracker"] as? Map<*, *>)
            val pollingInterval = parsePollingInterval(map["polling"] as? Map<*, *>)
            val workspaceRoot = parseWorkspaceRoot(map["workspace"] as? Map<*, *>, workflowFileDir)
            val hooksConfig = parseHooksConfig(map["hooks"] as? Map<*, *>)
            val agentSection = parseAgentSection(map["agent"] as? Map<*, *>)
            val codexSection = parseCodexSection(map["codex"] as? Map<*, *>, map["opencode"] as? Map<*, *>, agentSection.kind)

            ServiceConfig(
                trackerKind = trackerSection.kind,
                trackerEndpoint = trackerSection.endpoint,
                trackerApiKey = trackerSection.apiKey,
                trackerProjectSlug = trackerSection.projectSlug,
                requiredLabels = trackerSection.requiredLabels,
                activeStates = trackerSection.activeStates,
                terminalStates = trackerSection.terminalStates,
                pollIntervalMs = pollingInterval,
                workspaceRoot = workspaceRoot,
                hooks = hooksConfig,
                maxConcurrentAgents = agentSection.maxConcurrentAgents,
                maxTurns = agentSection.maxTurns,
                maxRetryBackoffMs = agentSection.maxRetryBackoffMs,
                maxConcurrentAgentsByState = agentSection.perState,
                agentKind = agentSection.kind,
                codexCommand = codexSection.command,
                codexApprovalPolicy = codexSection.approvalPolicy,
                codexThreadSandbox = codexSection.threadSandbox,
                codexTurnSandboxPolicy = codexSection.turnSandboxPolicy,
                opencodeCommand = codexSection.opencodeCommand,
                turnTimeoutMs = codexSection.turnTimeoutMs,
                readTimeoutMs = codexSection.readTimeoutMs,
                stallTimeoutMs = codexSection.stallTimeoutMs
            )
        }

        private data class TrackerSection(
            val kind: String?,
            val endpoint: String,
            val apiKey: String?,
            val projectSlug: String?,
            val requiredLabels: List<String>,
            val activeStates: List<String>,
            val terminalStates: List<String>
        )

        private data class AgentSection(
            val kind: String,
            val maxConcurrentAgents: Int,
            val maxTurns: Int,
            val maxRetryBackoffMs: Long,
            val perState: Map<String, Int>
        )

        private data class CodexSection(
            val command: String,
            val approvalPolicy: Map<String, Any?>?,
            val threadSandbox: String?,
            val turnSandboxPolicy: Map<String, Any?>?,
            val opencodeCommand: String,
            val turnTimeoutMs: Long,
            val readTimeoutMs: Long,
            val stallTimeoutMs: Long
        )

        private fun parseTrackerSection(map: Map<*, *>?): TrackerSection {
            val kind = map?.get("kind") as? String
            if (map != null && kind.isNullOrBlank()) {
                throw IllegalStateException("tracker.kind is required")
            }
            val endpoint = (map?.get("endpoint") as? String)
                ?: "https://api.linear.app/graphql"
            val apiKey = resolveEnvRef(map?.get("api_key") as? String)
            val projectSlug = map?.get("project_slug") as? String
            val requiredLabels = (map?.get("required_labels") as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?: emptyList()
            val activeStates = (map?.get("active_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Todo", "In Progress")
            val terminalStates = (map?.get("terminal_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
            return TrackerSection(kind, endpoint, apiKey, projectSlug, requiredLabels, activeStates, terminalStates)
        }

        private fun parsePollingInterval(map: Map<*, *>?): Long =
            (map?.get("interval_ms") as? Number)?.toLong() ?: 30_000L

        private fun parseWorkspaceRoot(map: Map<*, *>?, workflowFileDir: String): Path {
            val raw = (map?.get("root") as? String)
                ?: "${System.getProperty("java.io.tmpdir")}/symphony_workspaces"
            return resolvePath(raw, workflowFileDir)
        }

        private fun parseHooksConfig(map: Map<*, *>?): HooksConfig = HooksConfig(
            afterCreate = map?.get("after_create") as? String,
            beforeRun = map?.get("before_run") as? String,
            afterRun = map?.get("after_run") as? String,
            beforeRemove = map?.get("before_remove") as? String,
            timeoutMs = (map?.get("timeout_ms") as? Number)?.toLong() ?: 60_000L
        )

        private fun parseAgentSection(map: Map<*, *>?): AgentSection {
            val kind = (map?.get("kind") as? String)?.lowercase() ?: "codex"
            if (kind !in listOf("codex", "opencode")) {
                throw IllegalStateException("agent.kind must be 'codex' or 'opencode', got '$kind'")
            }
            val maxConcurrentAgents = (map?.get("max_concurrent_agents") as? Number)?.toInt() ?: 10
            val maxTurns = (map?.get("max_turns") as? Number)?.toInt() ?: 20
            if (maxTurns <= 0) {
                throw IllegalStateException("agent.max_turns must be positive")
            }
            val maxRetryBackoffMs = (map?.get("max_retry_backoff_ms") as? Number)?.toLong() ?: 300_000L
            val perState = (map?.get("max_concurrent_agents_by_state") as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    val key = (k as? String)?.lowercase() ?: return@mapNotNull null
                    val value = (v as? Number)?.toInt() ?: return@mapNotNull null
                    if (value <= 0) null else key to value
                }
                ?.toMap()
                ?: emptyMap()
            return AgentSection(kind, maxConcurrentAgents, maxTurns, maxRetryBackoffMs, perState)
        }

        private fun parseCodexSection(codex: Map<*, *>?, opencode: Map<*, *>?, agentKind: String): CodexSection {
            val command = (codex?.get("command") as? String) ?: "codex app-server"
            if (agentKind == "codex" && command.isBlank()) {
                throw IllegalStateException("codex.command must not be empty")
            }
            val opencodeCommand = (opencode?.get("command") as? String) ?: "opencode"
            if (agentKind == "opencode" && opencodeCommand.isBlank()) {
                throw IllegalStateException("opencode.command must not be empty")
            }
            return CodexSection(
                command = command,
                approvalPolicy = @Suppress("UNCHECKED_CAST") (codex?.get("approval_policy") as? Map<String, Any?>),
                threadSandbox = codex?.get("thread_sandbox") as? String,
                turnSandboxPolicy = @Suppress("UNCHECKED_CAST") (codex?.get("turn_sandbox_policy") as? Map<String, Any?>),
                opencodeCommand = opencodeCommand,
                turnTimeoutMs = (codex?.get("turn_timeout_ms") as? Number)?.toLong() ?: 3_600_000L,
                readTimeoutMs = (codex?.get("read_timeout_ms") as? Number)?.toLong() ?: 5_000L,
                stallTimeoutMs = (codex?.get("stall_timeout_ms") as? Number)?.toLong() ?: 300_000L
            )
        }

        internal fun resolveEnvRef(value: String?): String? {
            if (value == null) return null
            val envMatch = Regex("""^\$([A-Z_][A-Z0-9_]*)$""").matchEntire(value)
            return if (envMatch != null) {
                val name = envMatch.groupValues[1]
                System.getProperty(name) ?: System.getenv(name)
            } else {
                value
            }
        }

        private fun resolvePath(raw: String, workflowFileDir: String): Path {
            val expanded = expandTilde(raw)
            val envResolved = resolveEnvRef(expanded) ?: expanded
            val withEnv = envResolved
                .replace("\${java.io.tmpdir}", System.getProperty("java.io.tmpdir"))
            val path = Paths.get(withEnv)
            return if (path.isAbsolute) path else Paths.get(workflowFileDir).resolve(path).normalize()
        }

        private fun expandTilde(path: String): String {
            if (path.startsWith("~/")) {
                return "${System.getProperty("user.home")}/" + path.removePrefix("~/")
            }
            return path
        }
    }
}

data class HooksConfig(
    val afterCreate: String?,
    val beforeRun: String?,
    val afterRun: String?,
    val beforeRemove: String?,
    val timeoutMs: Long
)
