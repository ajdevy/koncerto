package com.anomaly.koncerto.core.config

import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.result.runCatchingResult
import java.nio.file.Path
import java.nio.file.Paths

data class ServiceConfig(
    val pollIntervalMs: Long = 30000,
    val projects: Map<String, ProjectConfig> = emptyMap(),
    val hooks: HooksConfig = HooksConfig(null, null, null, null, 60000),
    val gitConfig: GitConfig = GitConfig(),
    val deprecationWarnings: List<String> = emptyList()
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
            val (pollIntervalMs, deprecations) = parsePollIntervalMs(map)
            val hooks = parseHooksConfig(map["hooks"] as? Map<*, *>)
            val git = parseGitConfig(map["git"] as? Map<*, *>)
            val projects = parseProjects(map["projects"] as? Map<*, *>, workflowFileDir)

            ServiceConfig(
                pollIntervalMs = pollIntervalMs,
                projects = projects,
                hooks = hooks,
                gitConfig = git,
                deprecationWarnings = deprecations
            )
        }

        private fun parsePollIntervalMs(map: Map<String, Any?>): Pair<Long, List<String>> {
            val legacy = (map["polling"] as? Map<*, *>)?.get("interval_ms")
            val modern = map["poll_interval_ms"]
            return if (legacy != null && modern == null) {
                val ms = (legacy as? Number)?.toLong() ?: 30_000L
                ms to listOf("polling.interval_ms is deprecated; use poll_interval_ms at the top level")
            } else {
                val raw = modern ?: legacy
                val ms = (raw as? Number)?.toLong() ?: 30_000L
                ms to emptyList()
            }
        }

        private fun parseProjects(
            raw: Map<*, *>?,
            workflowFileDir: String
        ): Map<String, ProjectConfig> {
            if (raw == null) return emptyMap()
            return raw.mapNotNull { (key, value) ->
                val slug = (key as? String) ?: return@mapNotNull null
                val projectMap = value as? Map<*, *> ?: return@mapNotNull null
                slug to parseProjectConfig(projectMap, workflowFileDir)
            }.toMap()
        }

        private fun parseProjectConfig(
            map: Map<*, *>,
            workflowFileDir: String
        ): ProjectConfig {
            val tracker = parseTrackerConfig(map["tracker"] as? Map<*, *>)
            val workspace = parseWorkspaceConfig(map["workspace"] as? Map<*, *>, workflowFileDir)
            val agent = parseAgentConfig(map["agent"] as? Map<*, *>)
            return ProjectConfig(tracker = tracker, workspace = workspace, agent = agent)
        }

        internal fun parseTrackerConfig(map: Map<*, *>?): TrackerConfig {
            val kind = map?.get("kind") as? String
            if (map != null && kind.isNullOrBlank()) {
                throw IllegalStateException("tracker.kind is required")
            }
            val endpoint = (map?.get("endpoint") as? String) ?: "https://api.linear.app/graphql"
            val apiKey = resolveEnvRef(map?.get("api_key") as? String) ?: ""
            if (apiKey.isBlank()) {
                throw IllegalStateException("tracker.api_key is required")
            }
            val projectSlug = map?.get("project_slug") as? String ?: ""
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
            val blockedState = (map?.get("blocked_state") as? String) ?: "Blocked"
            val projectAdmin = map?.get("project_admin") as? String
            return TrackerConfig(
                kind = kind ?: "",
                endpoint = endpoint,
                apiKey = apiKey,
                projectSlug = projectSlug,
                requiredLabels = requiredLabels,
                activeStates = activeStates,
                terminalStates = terminalStates,
                blockedState = blockedState,
                projectAdmin = projectAdmin
            )
        }

        private fun parseWorkspaceConfig(map: Map<*, *>?, workflowFileDir: String): WorkspaceConfig {
            val raw = (map?.get("root") as? String)
                ?: "${System.getProperty("java.io.tmpdir")}/symphony_workspaces"
            val resolved = resolvePath(raw, workflowFileDir)
            return WorkspaceConfig(root = resolved.toString())
        }

        internal fun parseAgentConfig(map: Map<*, *>?): AgentProjectConfig {
            val kind = (map?.get("kind") as? String)?.lowercase() ?: "opencode"
            val command = map?.get("command") as? String
            val maxConcurrentAgents = (map?.get("max_concurrent_agents") as? Number)?.toInt() ?: 2
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
            val turnTimeoutMs = (map?.get("turn_timeout_ms") as? Number)?.toLong() ?: 3_600_000L
            val readTimeoutMs = (map?.get("read_timeout_ms") as? Number)?.toLong() ?: 5_000L
            val stallTimeoutMs = (map?.get("stall_timeout_ms") as? Number)?.toLong() ?: 300_000L
            val stages = parseStages(map)
            val agents = parseAgents(map)

            return AgentProjectConfig(
                kind = kind,
                command = command,
                maxConcurrentAgents = maxConcurrentAgents,
                maxTurns = maxTurns,
                maxRetryBackoffMs = maxRetryBackoffMs,
                maxConcurrentAgentsByState = perState,
                turnTimeoutMs = turnTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                stallTimeoutMs = stallTimeoutMs,
                stages = stages,
                agents = agents
            )
        }

        internal fun parseStages(agentMap: Map<*, *>?): Map<String, StageAgentConfig> {
            val rawStages = agentMap?.get("stages") as? Map<*, *> ?: return emptyMap()
            return rawStages.mapNotNull { (k, v) ->
                val stageName = (k as? String)?.lowercase() ?: return@mapNotNull null
                val stageMap = v as? Map<*, *> ?: return@mapNotNull null
                stageName to StageAgentConfig(
                    prompt = stageMap["prompt"] as? String,
                    model = stageMap["model"] as? String,
                    maxConcurrent = (stageMap["max_concurrent"] as? Number)?.toInt(),
                    agentKind = (stageMap["agent_kind"] as? String)?.lowercase(),
                    command = stageMap["command"] as? String,
                    onCompleteState = stageMap["on_complete_state"] as? String,
                    agent = stageMap["agent"] as? String
                )
            }.toMap()
        }

        internal fun parseAgents(agentMap: Map<*, *>?): Map<String, AgentProviderConfig> {
            val rawAgents = agentMap?.get("agents") as? Map<*, *> ?: return emptyMap()
            return rawAgents.mapNotNull { (k, v) ->
                val name = (k as? String) ?: return@mapNotNull null
                val agentMap = v as? Map<*, *> ?: return@mapNotNull null
                val kind = (agentMap["kind"] as? String) ?: return@mapNotNull null
                name to AgentProviderConfig(
                    kind = kind,
                    command = agentMap["command"] as? String,
                    model = agentMap["model"] as? String,
                    maxConcurrent = (agentMap["max_concurrent"] as? Number)?.toInt()
                )
            }.toMap()
        }

        internal fun parseHooksConfig(map: Map<*, *>?): HooksConfig = HooksConfig(
            afterCreate = map?.get("after_create") as? String,
            beforeRun = map?.get("before_run") as? String,
            afterRun = map?.get("after_run") as? String,
            beforeRemove = map?.get("before_remove") as? String,
            timeoutMs = (map?.get("timeout_ms") as? Number)?.toLong() ?: 60_000L
        )

        internal fun parseGitConfig(map: Map<*, *>?): GitConfig {
            if (map == null) return GitConfig()
            return GitConfig(
                enabled = (map["enabled"] as? Boolean) ?: false,
                branchPrefix = (map["branch_prefix"] as? String) ?: "feature/",
                autoCommit = (map["auto_commit"] as? Boolean) ?: true,
                autoPush = (map["auto_push"] as? Boolean) ?: false,
                createPr = (map["create_pr"] as? Boolean) ?: false,
                prBase = (map["pr_base"] as? String) ?: "main"
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

        internal fun resolvePath(raw: String, workflowFileDir: String): Path {
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

@kotlinx.serialization.Serializable
data class AgentProviderConfig(
    val kind: String,
    val command: String? = null,
    val model: String? = null,
    val maxConcurrent: Int? = null
)

@kotlinx.serialization.Serializable
data class StageAgentConfig(
    val prompt: String?,
    val model: String?,
    val maxConcurrent: Int?,
    val agentKind: String?,
    val command: String?,
    val onCompleteState: String?,
    val agent: String? = null
)

data class GitConfig(
    val enabled: Boolean = false,
    val branchPrefix: String = "feature/",
    val autoCommit: Boolean = true,
    val autoPush: Boolean = false,
    val createPr: Boolean = false,
    val prBase: String = "main"
)
