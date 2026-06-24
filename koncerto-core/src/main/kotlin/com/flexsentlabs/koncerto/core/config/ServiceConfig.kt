package com.flexsentlabs.koncerto.core.config

import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.core.result.runCatchingResult
import java.nio.file.Path
import java.nio.file.Paths

data class ServiceConfig(
    val pollIntervalMs: Long = 30000,
    val projects: Map<String, ProjectConfig> = emptyMap(),
    val hooks: HooksConfig = HooksConfig(null, null, null, null, 60000),
    val gitConfig: GitConfig = GitConfig(),
    val adminApiKey: String? = null,
    val deprecationWarnings: List<String> = emptyList(),
    val demoRecording: DemoRecordingConfig = DemoRecordingConfig()
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
        ): Result<ServiceConfig, Exception> = runCatchingResult {
            val (pollIntervalMs, deprecations) = parsePollIntervalMs(map)
            val hooks = parseHooksConfig(map["hooks"] as? Map<*, *>)
            val git = parseGitConfig(map["git"] as? Map<*, *>)
            val projects = parseProjects(map["projects"] as? Map<*, *>, workflowFileDir)
            val adminApiKey = (map["admin"] as? Map<*, *>)?.get("apiKey") as? String
            val demoRecording = parseDemoRecordingConfig(map["demo_recording"] as? Map<*, *>)

            ServiceConfig(
                pollIntervalMs = pollIntervalMs,
                projects = projects,
                hooks = hooks,
                gitConfig = git,
                adminApiKey = adminApiKey,
                deprecationWarnings = deprecations,
                demoRecording = demoRecording
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
            val rateLimiter = parseRateLimiterConfig(map["rate_limiter"] as? Map<*, *>)
            val circuitBreaker = parseCircuitBreakerConfig(map["circuit_breaker"] as? Map<*, *>)
            val notifications = parseNotificationsConfig(map["notifications"] as? Map<*, *>)
            return ProjectConfig(
                tracker = tracker, workspace = workspace, agent = agent,
                rateLimiter = rateLimiter, circuitBreaker = circuitBreaker,
                notifications = notifications
            )
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
            val maxRetries = (map?.get("max_retries") as? Number)?.toInt() ?: 3
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
            val heartbeatIntervalMs = (map?.get("heartbeat_interval_ms") as? Number)?.toLong() ?: 30_000L
            val heartbeatTimeoutMs = (map?.get("heartbeat_timeout_ms") as? Number)?.toLong() ?: 90_000L
            val stages = parseStages(map)
            val agents = parseAgents(map)
            val routingRules = parseRoutingRules(map)
            val docker = parseDockerConfig(map)

            return AgentProjectConfig(
                kind = kind,
                command = command,
                maxConcurrentAgents = maxConcurrentAgents,
                maxTurns = maxTurns,
                maxRetries = maxRetries,
                maxRetryBackoffMs = maxRetryBackoffMs,
                maxConcurrentAgentsByState = perState,
                turnTimeoutMs = turnTimeoutMs,
                readTimeoutMs = readTimeoutMs,
                stallTimeoutMs = stallTimeoutMs,
                heartbeatIntervalMs = heartbeatIntervalMs,
                heartbeatTimeoutMs = heartbeatTimeoutMs,
                stages = stages,
                agents = agents,
                routingRules = routingRules,
                docker = docker
            )
        }

        internal fun parseStages(agentMap: Map<*, *>?): Map<String, StageAgentConfig> {
            val rawStages = agentMap?.get("stages") as? Map<*, *> ?: return emptyMap()
            return rawStages.mapNotNull { (k, v) ->
                val stageName = (k as? String)?.lowercase() ?: return@mapNotNull null
                val stageMap = v as? Map<*, *> ?: return@mapNotNull null
            stageName to StageAgentConfig(
                prompt = resolveInlineEnvRefs(stageMap["prompt"] as? String),
                model = resolveInlineEnvRefs(stageMap["model"] as? String),
                effort = stageMap["effort"] as? String,
                maxConcurrent = (stageMap["max_concurrent"] as? Number)?.toInt(),
                agentKind = (stageMap["agent_kind"] as? String)?.lowercase(),
                command = resolveInlineEnvRefs(stageMap["command"] as? String),
                onCompleteState = stageMap["on_complete_state"] as? String,
                onFailureState = stageMap["on_failure_state"] as? String,
                maxReviewAttempts = (stageMap["max_review_attempts"] as? Number)?.toInt(),
                agent = stageMap["agent"] as? String,
                    followUp = (stageMap["follow_up"] as? Map<*, *>)?.let { f ->
                        FollowUpConfig(
                            titleTemplate = (f["title_template"] as? String) ?: return@let null,
                            state = (f["state"] as? String) ?: return@let null,
                            descriptionTemplate = f["description_template"] as? String,
                            labels = (f["labels"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                            linkType = (f["link_type"] as? String) ?: "blocks",
                            assignee = f["assignee"] as? String,
                            agent = f["agent"] as? String
                        )
                    }
                )
            }.toMap()
        }

        internal fun parseRoutingRules(agentMap: Map<*, *>?): List<RoutingRule> {
            val raw = agentMap?.get("routing_rules") as? List<*> ?: return emptyList()
            return raw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val useAgent = (map["use_agent"] as? String) ?: return@mapNotNull null
                RoutingRule(
                    ifLabel = map["if_label"] as? String,
                    ifLabelPrefix = map["if_label_prefix"] as? String,
                    ifState = map["if_state"] as? String,
                    ifPriority = (map["if_priority"] as? Number)?.toInt(),
                    ifPriorityMax = (map["if_priority_max"] as? Number)?.toInt(),
                    useAgent = useAgent,
                    priority = (map["priority"] as? Number)?.toInt() ?: 0
                )
            }.sortedByDescending { it.priority }
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
                    effort = agentMap["effort"] as? String,
                    maxConcurrent = (agentMap["max_concurrent"] as? Number)?.toInt()
                )
            }.toMap()
        }

        internal fun parseDockerConfig(agentMap: Map<*, *>?): DockerConfig? {
            val dockerMap = agentMap?.get("docker") as? Map<*, *> ?: return null
            return DockerConfig(
                enabled = (dockerMap["enabled"] as? Boolean) ?: true,
                image = (dockerMap["image"] as? String) ?: "koncerto-agent:latest",
                cpu = (dockerMap["cpu"] as? String) ?: "auto",
                memory = (dockerMap["memory"] as? String) ?: "auto",
                network = (dockerMap["network"] as? Boolean) ?: true,
                dockerfile = (dockerMap["dockerfile"] as? String) ?: "Dockerfile.agent"
            )
        }

        internal fun parseRateLimiterConfig(map: Map<*, *>?): RateLimiterConfig? {
            if (map == null) return null
            return RateLimiterConfig(
                requestsPerSecond = (map["requests_per_second"] as? Number)?.toInt() ?: 10,
                maxBurst = (map["max_burst"] as? Number)?.toInt() ?: 20
            )
        }

        internal fun parseCircuitBreakerConfig(map: Map<*, *>?): CircuitBreakerConfig? {
            if (map == null) return null
            return CircuitBreakerConfig(
                failureThreshold = (map["failure_threshold"] as? Number)?.toInt() ?: 5,
                resetTimeoutMs = (map["reset_timeout_ms"] as? Number)?.toLong() ?: 30_000
            )
        }

        internal fun parseNotificationsConfig(map: Map<*, *>?): NotificationsConfig {
            if (map == null) return NotificationsConfig(onCompleted = false, onFailed = false, onStalled = false, onClarification = false)
            val telegramMap = map["telegram"] as? Map<*, *>
            val emailMap = map["email"] as? Map<*, *>
            val webhookMap = map["webhook"] as? Map<*, *>
            return NotificationsConfig(
                onCompleted = (map["on_completed"] as? Boolean)
                    ?: (telegramMap != null || emailMap != null || webhookMap != null),
                onFailed = (map["on_failed"] as? Boolean)
                    ?: (telegramMap != null || emailMap != null || webhookMap != null),
                onStalled = (map["on_stalled"] as? Boolean)
                    ?: (telegramMap != null || emailMap != null || webhookMap != null),
                onClarification = (map["on_clarification"] as? Boolean)
                    ?: (telegramMap != null || emailMap != null || webhookMap != null),
                onLimit = (map["on_limit"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                limitCooldownMs = (map["limit_cooldown_ms"] as? Number)?.toLong() ?: 300_000L,
                telegram = telegramMap?.let { TelegramConfig(
                    botToken = resolveEnvRef(it["bot_token"] as? String) ?: "",
                    chatId = it["chat_id"] as? String ?: ""
                )},
                email = emailMap?.let { EmailConfig(
                    smtpHost = it["smtp_host"] as? String ?: "",
                    smtpPort = (it["smtp_port"] as? Number)?.toInt() ?: 587,
                    username = resolveEnvRef(it["username"] as? String),
                    password = resolveEnvRef(it["password"] as? String),
                    from = it["from"] as? String ?: "",
                    to = it["to"] as? String ?: ""
                )},
                webhook = webhookMap?.let { WebhookConfig(
                    url = it["url"] as? String ?: "",
                    headers = (it["headers"] as? Map<*, *>)
                        ?.mapKeys { k -> k.key.toString() }
                        ?.mapValues { v -> resolveEnvRef(v.value as? String) ?: v.value.toString() }
                        ?: emptyMap()
                )}
            )
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
                prBase = (map["pr_base"] as? String) ?: "main",
                remoteUrl = resolveEnvRef(map["remote_url"] as? String) ?: ""
            )
        }

        internal fun parseDemoRecordingConfig(map: Map<*, *>?): DemoRecordingConfig {
            if (map == null) return DemoRecordingConfig()
            val platformMap = map["platform"] as? Map<*, *>
            val qualityMap = map["quality"] as? Map<*, *>
            val storageMap = map["storage"] as? Map<*, *>
            val aiMap = map["ai"] as? Map<*, *>
            val retryMap = map["retry"] as? Map<*, *>
            val errorMap = map["error"] as? Map<*, *>
            return DemoRecordingConfig(
                enabled = (map["enabled"] as? Boolean) ?: false,
                trigger = (map["trigger"] as? String) ?: "review_passed",
                targetUrl = resolveEnvRef(map["target_url"] as? String) ?: "",
                cleanupIntervalHours = (map["cleanup_interval_hours"] as? Number)?.toInt() ?: 24,
                platform = DemoRecordingConfig.PlatformConfig(
                    web = (platformMap?.get("web") as? String) ?: "playwright",
                    terminal = (platformMap?.get("terminal") as? String) ?: "asciinema"
                ),
                quality = DemoRecordingConfig.QualityConfig(
                    resolution = (qualityMap?.get("resolution") as? String) ?: "1280x720",
                    fps = (qualityMap?.get("fps") as? Number)?.toInt() ?: 10,
                    codec = (qualityMap?.get("codec") as? String) ?: "vp9"
                ),
                storage = storageMap?.let { s ->
                    DemoRecordingConfig.StorageConfig(
                        r2Endpoint = resolveEnvRef(s["r2_endpoint"] as? String) ?: "",
                        r2Bucket = resolveEnvRef(s["r2_bucket"] as? String) ?: "",
                        r2AccessKey = resolveEnvRef(s["r2_access_key"] as? String) ?: "",
                        r2SecretKey = resolveEnvRef(s["r2_secret_key"] as? String) ?: "",
                        publicUrlBase = resolveEnvRef(s["public_url_base"] as? String) ?: "",
                        presignedUrlTtl = (s["presigned_url_ttl"] as? Number)?.toLong() ?: 604800,
                        region = s["region"] as? String ?: "auto"
                    )
                },
                ai = DemoRecordingConfig.AiConfig(
                    model = (aiMap?.get("model") as? String) ?: "free",
                    timeline = (aiMap?.get("timeline") as? Boolean) ?: false,
                    reproSteps = (aiMap?.get("repro_steps") as? Boolean) ?: false
                ),
                retry = DemoRecordingConfig.RetryConfig(
                    maxAttempts = (retryMap?.get("max_attempts") as? Number)?.toInt() ?: 3,
                    backoff = (retryMap?.get("backoff") as? String) ?: "exponential"
                ),
                error = DemoRecordingConfig.ErrorConfig(
                    onFailure = (errorMap?.get("on_failure") as? String) ?: "mark_blocked"
                )
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

        internal fun resolveInlineEnvRefs(value: String?): String? {
            if (value == null) return null
            return Regex("""\$([A-Z_][A-Z0-9_]*)|\$\{([A-Z_][A-Z0-9_]*)\}""").replace(value) { match ->
                val name = match.groupValues[1].ifBlank { match.groupValues[2] }
                System.getenv(name) ?: System.getProperty(name) ?: match.value
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
data class FollowUpConfig(
    val titleTemplate: String,
    val state: String,
    val descriptionTemplate: String? = null,
    val labels: List<String> = emptyList(),
    val linkType: String = "blocks",
    val assignee: String? = null,
    val agent: String? = null
)

@kotlinx.serialization.Serializable
data class AgentProviderConfig(
    val kind: String,
    val command: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val maxConcurrent: Int? = null
)

@kotlinx.serialization.Serializable
data class StageAgentConfig(
    val prompt: String?,
    val model: String?,
    val effort: String?,
    val maxConcurrent: Int?,
    val agentKind: String?,
    val command: String?,
    val onCompleteState: String?,
    val onFailureState: String? = null,
    val maxReviewAttempts: Int? = null,
    val agent: String? = null,
    val followUp: FollowUpConfig? = null,
    val crossProjectFollowUp: CrossProjectFollowUpConfig? = null
)

data class GitConfig(
    val enabled: Boolean = false,
    val branchPrefix: String = "feature/",
    val autoCommit: Boolean = true,
    val autoPush: Boolean = false,
    val createPr: Boolean = false,
    val prBase: String = "main",
    val remoteUrl: String = ""
)

data class DemoRecordingConfig(
    val enabled: Boolean = false,
    val trigger: String = "review_passed",
    val targetUrl: String = "",
    val platform: PlatformConfig = PlatformConfig(),
    val quality: QualityConfig = QualityConfig(),
    val storage: StorageConfig? = null,
    val ai: AiConfig = AiConfig(),
    val retry: RetryConfig = RetryConfig(),
    val error: ErrorConfig = ErrorConfig(),
    val cleanupIntervalHours: Int = 24
) {
    data class PlatformConfig(
        val web: String = "playwright",
        val terminal: String = "asciinema"
    )
    data class QualityConfig(
        val resolution: String = "1280x720",
        val fps: Int = 10,
        val codec: String = "vp9"
    ) {
        val width: Int get() = resolution.split("x").firstOrNull()?.toIntOrNull() ?: 1280
        val height: Int get() = resolution.split("x").lastOrNull()?.toIntOrNull() ?: 720
    }
    data class StorageConfig(
        val r2Endpoint: String = "",
        val r2Bucket: String = "",
        val r2AccessKey: String = "",
        val r2SecretKey: String = "",
        val publicUrlBase: String = "",
        val presignedUrlTtl: Long = 604800,
        val region: String = "auto"
    )
    data class AiConfig(
        val model: String = "free",
        val timeline: Boolean = false,
        val reproSteps: Boolean = false
    )
    data class RetryConfig(
        val maxAttempts: Int = 3,
        val backoff: String = "exponential"
    )
    data class ErrorConfig(
        val onFailure: String = "mark_blocked"
    )
}
