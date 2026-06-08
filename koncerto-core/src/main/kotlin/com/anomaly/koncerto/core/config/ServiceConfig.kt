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
            val tracker = map["tracker"] as? Map<*, *>
            val polling = map["polling"] as? Map<*, *>
            val workspace = map["workspace"] as? Map<*, *>
            val hooks = map["hooks"] as? Map<*, *>
            val agent = map["agent"] as? Map<*, *>
            val codex = map["codex"] as? Map<*, *>

            val trackerKind = tracker?.get("kind") as? String
            if (tracker != null && trackerKind.isNullOrBlank()) {
                throw IllegalStateException("tracker.kind is required")
            }

            val activeStates = (tracker?.get("active_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Todo", "In Progress")
            val terminalStates = (tracker?.get("terminal_states") as? List<*>)
                ?.filterIsInstance<String>()
                ?: listOf("Closed", "Cancelled", "Canceled", "Duplicate", "Done")

            val requiredLabels = (tracker?.get("required_labels") as? List<*>)
                ?.filterIsInstance<String>()
                ?.map { it.trim() }
                ?: emptyList()

            val workspaceRootRaw = (workspace?.get("root") as? String)
                ?: "${System.getProperty("java.io.tmpdir")}/symphony_workspaces"
            val workspaceRoot = resolvePath(workspaceRootRaw, workflowFileDir)

            val hooksConfig = HooksConfig(
                afterCreate = hooks?.get("after_create") as? String,
                beforeRun = hooks?.get("before_run") as? String,
                afterRun = hooks?.get("after_run") as? String,
                beforeRemove = hooks?.get("before_remove") as? String,
                timeoutMs = (hooks?.get("timeout_ms") as? Number)?.toLong() ?: 60_000L
            )

            val perState = (agent?.get("max_concurrent_agents_by_state") as? Map<*, *>)
                ?.mapNotNull { (k, v) ->
                    val key = (k as? String)?.lowercase() ?: return@mapNotNull null
                    val value = (v as? Number)?.toInt() ?: return@mapNotNull null
                    if (value <= 0) null else key to value
                }
                ?.toMap()
                ?: emptyMap()

            val agentKind = (agent?.get("kind") as? String)?.lowercase() ?: "codex"
            if (agentKind !in listOf("codex", "opencode")) {
                throw IllegalStateException("agent.kind must be 'codex' or 'opencode', got '$agentKind'")
            }

            val codexCommand = (codex?.get("command") as? String) ?: "codex app-server"
            if (agentKind == "codex" && codexCommand.isBlank()) {
                throw IllegalStateException("codex.command must not be empty")
            }

            val opencodeCommand = (map["opencode"] as? Map<*, *>)?.get("command") as? String ?: "opencode"
            if (agentKind == "opencode" && opencodeCommand.isBlank()) {
                throw IllegalStateException("opencode.command must not be empty")
            }

            val maxTurns = (agent?.get("max_turns") as? Number)?.toInt() ?: 20
            if (maxTurns <= 0) {
                throw IllegalStateException("agent.max_turns must be positive")
            }

            ServiceConfig(
                trackerKind = trackerKind,
                trackerEndpoint = (tracker?.get("endpoint") as? String)
                    ?: "https://api.linear.app/graphql",
                trackerApiKey = resolveEnvRef(tracker?.get("api_key") as? String),
                trackerProjectSlug = tracker?.get("project_slug") as? String,
                requiredLabels = requiredLabels,
                activeStates = activeStates,
                terminalStates = terminalStates,
                pollIntervalMs = (polling?.get("interval_ms") as? Number)?.toLong() ?: 30_000L,
                workspaceRoot = workspaceRoot,
                hooks = hooksConfig,
                maxConcurrentAgents = (agent?.get("max_concurrent_agents") as? Number)?.toInt() ?: 10,
                maxTurns = maxTurns,
                maxRetryBackoffMs = (agent?.get("max_retry_backoff_ms") as? Number)?.toLong() ?: 300_000L,
                maxConcurrentAgentsByState = perState,
                agentKind = agentKind,
                codexCommand = codexCommand,
                codexApprovalPolicy = @Suppress("UNCHECKED_CAST") (codex?.get("approval_policy") as? Map<String, Any?>),
                codexThreadSandbox = codex?.get("thread_sandbox") as? String,
                codexTurnSandboxPolicy = @Suppress("UNCHECKED_CAST") (codex?.get("turn_sandbox_policy") as? Map<String, Any?>),
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
