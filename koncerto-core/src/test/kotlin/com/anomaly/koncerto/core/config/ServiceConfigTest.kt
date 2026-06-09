package com.anomaly.koncerto.core.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class ServiceConfigTest {

    @Test
    fun `defaults are applied when fields are missing`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")

        assertThat(config.pollIntervalMs).isEqualTo(30_000L)
        assertThat(config.activeStates).containsExactly("Todo", "In Progress")
        assertThat(config.terminalStates).containsExactly("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
        assertThat(config.requiredLabels).isEqualTo(emptyList())
        assertThat(config.maxConcurrentAgents).isEqualTo(10)
        assertThat(config.maxTurns).isEqualTo(20)
        assertThat(config.maxRetryBackoffMs).isEqualTo(300_000L)
        assertThat(config.turnTimeoutMs).isEqualTo(3_600_000L)
        assertThat(config.readTimeoutMs).isEqualTo(5_000L)
        assertThat(config.stallTimeoutMs).isEqualTo(300_000L)
        assertThat(config.hooksTimeoutMs()).isEqualTo(60_000L)
        assertThat(config.codexCommand).isEqualTo("codex app-server")
        assertThat(config.agentKind).isEqualTo("codex")
        assertThat(config.opencodeCommand).isEqualTo("opencode")
        assertThat(config.gitConfig.enabled).isEqualTo(false)
        assertThat(config.gitConfig.branchPrefix).isEqualTo("feature/")
        assertThat(config.gitConfig.autoCommit).isEqualTo(true)
        assertThat(config.gitConfig.autoPush).isEqualTo(false)
        assertThat(config.gitConfig.createPr).isEqualTo(false)
        assertThat(config.gitConfig.prBase).isEqualTo("main")
        assertThat(config.blockedState).isEqualTo("Blocked")
        assertThat(config.projectAdmin).isNull()
    }

    @Test
    fun `agent kind defaults to codex when not specified`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
        assertThat(config.agentKind).isEqualTo("codex")
    }

    @Test
    fun `agent kind codex is accepted`() {
        val config = ServiceConfig.fromMap(
            mapOf("agent" to mapOf("kind" to "codex")),
            workflowFileDir = "/tmp"
        )
        assertThat(config.agentKind).isEqualTo("codex")
    }

    @Test
    fun `agent kind opencode is accepted`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "agent" to mapOf("kind" to "opencode"),
                "opencode" to mapOf("command" to "opencode-my-build")
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(config.agentKind).isEqualTo("opencode")
        assertThat(config.opencodeCommand).isEqualTo("opencode-my-build")
    }

    @Test
    fun `agent kind invalid throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("agent" to mapOf("kind" to "invalid-agent")),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `opencode command defaults to opencode`() {
        val config = ServiceConfig.fromMap(
            mapOf("agent" to mapOf("kind" to "opencode")),
            workflowFileDir = "/tmp"
        )
        assertThat(config.opencodeCommand).isEqualTo("opencode")
    }

    @Test
    fun `tracker kind is required and validated`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("tracker" to mapOf("project_slug" to "p")),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `linear api_key resolved from env var`() {
        try {
            System.setProperty("LINEAR_API_KEY_FOR_TEST", "secret")
            val config = ServiceConfig.fromMap(
                mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "\$LINEAR_API_KEY_FOR_TEST",
                        "project_slug" to "proj"
                    )
                ),
                workflowFileDir = "/tmp"
            )
            assertThat(config.trackerApiKey).isEqualTo("secret")
        } finally {
            System.clearProperty("LINEAR_API_KEY_FOR_TEST")
        }
    }

    @Test
    fun `workspace root expands tilde`() {
        val config = ServiceConfig.fromMap(
            mapOf("workspace" to mapOf("root" to "~/workspaces")),
            workflowFileDir = "/tmp"
        )
        assertThat(config.workspaceRoot.toString()).isEqualTo("${System.getProperty("user.home")}/workspaces")
    }

    @Test
    fun `relative workspace root resolves against workflow file dir`() {
        val config = ServiceConfig.fromMap(
            mapOf("workspace" to mapOf("root" to "ws")),
            workflowFileDir = "/some/dir"
        )
        assertThat(config.workspaceRoot.toString()).isEqualTo("/some/dir/ws")
    }

    @Test
    fun `stages defaults to empty when not specified`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
        assertThat(config.stages).isEqualTo(emptyMap())
    }

    @Test
    fun `parse single stage with all fields`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "agent" to mapOf(
                    "kind" to "opencode",
                    "stages" to mapOf(
                        "Todo" to mapOf(
                            "prompt" to "prompts/implement.md",
                            "model" to "claude-sonnet-4-5",
                            "max_concurrent" to 3,
                            "agent_kind" to "opencode",
                            "command" to "opencode-dev",
                            "on_complete_state" to "In Review"
                        )
                    )
                ),
                "opencode" to mapOf("command" to "opencode")
            ),
            workflowFileDir = "/tmp"
        )
        val todo = config.stages["todo"]
        assertThat(todo).isNotNull()
        assertThat(todo!!.prompt).isEqualTo("prompts/implement.md")
        assertThat(todo.model).isEqualTo("claude-sonnet-4-5")
        assertThat(todo.maxConcurrent).isEqualTo(3)
        assertThat(todo.agentKind).isEqualTo("opencode")
        assertThat(todo.command).isEqualTo("opencode-dev")
        assertThat(todo.onCompleteState).isEqualTo("In Review")
    }

    @Test
    fun `parse multiple stages`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "agent" to mapOf(
                    "kind" to "opencode",
                    "stages" to mapOf(
                        "Todo" to mapOf("prompt" to "impl.md", "max_concurrent" to 3),
                        "In Review" to mapOf("prompt" to "review.md", "max_concurrent" to 1)
                    )
                )
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(config.stages.keys).isEqualTo(setOf("todo", "in review"))
        assertThat(config.stages["todo"]?.prompt).isEqualTo("impl.md")
        assertThat(config.stages["in review"]?.prompt).isEqualTo("review.md")
    }

    @Test
    fun `stage with partial fields gets nulls for missing`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "agent" to mapOf(
                    "stages" to mapOf(
                        "Todo" to mapOf("prompt" to "only-prompt.md")
                    )
                )
            ),
            workflowFileDir = "/tmp"
        )
        val todo = config.stages["todo"]
        assertThat(todo).isNotNull()
        assertThat(todo!!.model).isNull()
        assertThat(todo.maxConcurrent).isNull()
        assertThat(todo.agentKind).isNull()
        assertThat(todo.command).isNull()
        assertThat(todo.onCompleteState).isNull()
    }

    @Test
    fun `git config defaults when not specified`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
        assertThat(config.gitConfig.enabled).isEqualTo(false)
        assertThat(config.gitConfig.branchPrefix).isEqualTo("feature/")
        assertThat(config.gitConfig.autoCommit).isEqualTo(true)
        assertThat(config.gitConfig.autoPush).isEqualTo(false)
        assertThat(config.gitConfig.createPr).isEqualTo(false)
        assertThat(config.gitConfig.prBase).isEqualTo("main")
    }

    @Test
    fun `git config parses all fields`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "git" to mapOf(
                    "enabled" to true,
                    "branch_prefix" to "fix/",
                    "auto_commit" to false,
                    "auto_push" to true,
                    "create_pr" to true,
                    "pr_base" to "develop"
                )
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(config.gitConfig.enabled).isEqualTo(true)
        assertThat(config.gitConfig.branchPrefix).isEqualTo("fix/")
        assertThat(config.gitConfig.autoCommit).isEqualTo(false)
        assertThat(config.gitConfig.autoPush).isEqualTo(true)
        assertThat(config.gitConfig.createPr).isEqualTo(true)
        assertThat(config.gitConfig.prBase).isEqualTo("develop")
    }

    @Test
    fun `git config partial fields get defaults`() {
        val config = ServiceConfig.fromMap(
            mapOf("git" to mapOf("enabled" to true)),
            workflowFileDir = "/tmp"
        )
        assertThat(config.gitConfig.enabled).isEqualTo(true)
        assertThat(config.gitConfig.branchPrefix).isEqualTo("feature/")
        assertThat(config.gitConfig.autoCommit).isEqualTo(true)
        assertThat(config.gitConfig.autoPush).isEqualTo(false)
        assertThat(config.gitConfig.createPr).isEqualTo(false)
        assertThat(config.gitConfig.prBase).isEqualTo("main")
    }

    @Test
    fun `blockedState defaults to Blocked when tracker has no blocked_state`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
        assertThat(config.blockedState).isEqualTo("Blocked")
    }

    @Test
    fun `blockedState parsed from tracker section`() {
        System.setProperty("KONCERTO_TEST_KEY", "k")
        try {
            val config = ServiceConfig.fromMap(
                mapOf("tracker" to mapOf("kind" to "linear", "project_slug" to "p",
                    "api_key" to "\$KONCERTO_TEST_KEY", "blocked_state" to "Waiting")),
                workflowFileDir = "/tmp"
            )
            assertThat(config.blockedState).isEqualTo("Waiting")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `projectAdmin defaults to null`() {
        val config = ServiceConfig.fromMap(emptyMap(), workflowFileDir = "/tmp")
        assertThat(config.projectAdmin).isNull()
    }

    @Test
    fun `projectAdmin parsed from tracker section`() {
        System.setProperty("KONCERTO_TEST_KEY", "k")
        try {
            val config = ServiceConfig.fromMap(
                mapOf("tracker" to mapOf("kind" to "linear", "project_slug" to "p",
                    "api_key" to "\$KONCERTO_TEST_KEY", "project_admin" to "user-1")),
                workflowFileDir = "/tmp"
            )
            assertThat(config.projectAdmin).isEqualTo("user-1")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `fromMap throws on invalid config`() {
        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            ServiceConfig.fromMap(
                mapOf("tracker" to mapOf("kind" to "", "project_slug" to "p")),
                workflowFileDir = "/tmp"
            )
        }
        assertThat(ex.message ?: "").contains("Invalid config")
    }

    @Test
    fun `parseTrackerSection with full custom fields`() {
        System.setProperty("KONCERTO_TEST_KEY", "k")
        try {
            val config = ServiceConfig.fromMap(
                mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "\$KONCERTO_TEST_KEY",
                        "project_slug" to "p",
                        "required_labels" to listOf(" bugfix ", " feature "),
                        "active_states" to listOf("Active", "Review"),
                        "terminal_states" to listOf("Done", "Wontfix"),
                        "blocked_state" to "Waiting",
                        "project_admin" to "admin-1"
                    )
                ),
                workflowFileDir = "/tmp"
            )
            assertThat(config.requiredLabels).isEqualTo(listOf("bugfix", "feature"))
            assertThat(config.activeStates).isEqualTo(listOf("Active", "Review"))
            assertThat(config.terminalStates).isEqualTo(listOf("Done", "Wontfix"))
            assertThat(config.blockedState).isEqualTo("Waiting")
            assertThat(config.projectAdmin).isEqualTo("admin-1")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `parsePollingInterval uses explicit value`() {
        val config = ServiceConfig.fromMap(
            mapOf("polling" to mapOf("interval_ms" to 5000)),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(5000L)
    }

    @Test
    fun `parseAgentSection maxTurns zero throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("agent" to mapOf("kind" to "codex", "max_turns" to 0)),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `parseAgentSection perState with valid entries`() {
        val config = ServiceConfig.fromMap(
            mapOf("agent" to mapOf(
                "kind" to "codex",
                "max_concurrent_agents_by_state" to mapOf(
                    "Todo" to 3,
                    "In Review" to 2
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.maxConcurrentAgentsByState["todo"]).isEqualTo(3)
        assertThat(config.maxConcurrentAgentsByState["in review"]).isEqualTo(2)
    }

    @Test
    fun `parseAgentSection perState filters invalid entries`() {
        val config = ServiceConfig.fromMap(
            mapOf("agent" to mapOf(
                "kind" to "codex",
                "max_concurrent_agents_by_state" to mapOf(
                    123 to 3,
                    "Review" to "not-a-number",
                    "Blocked" to 0,
                    "Todo" to 2
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.maxConcurrentAgentsByState.size).isEqualTo(1)
        assertThat(config.maxConcurrentAgentsByState["todo"]).isEqualTo(2)
    }

    @Test
    fun `parseCodexSection blank command for codex throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("codex" to mapOf("command" to "")),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `parseCodexSection blank command for opencode throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf(
                "agent" to mapOf("kind" to "opencode"),
                "opencode" to mapOf("command" to "")
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `parseCodexSection with explicit config values`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "codex" to mapOf(
                    "approval_policy" to mapOf("required" to true),
                    "thread_sandbox" to "docker",
                    "turn_sandbox_policy" to mapOf("readonly" to true),
                    "turn_timeout_ms" to 1_800_000,
                    "read_timeout_ms" to 10_000,
                    "stall_timeout_ms" to 600_000
                )
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(config.codexApprovalPolicy).isEqualTo(mapOf("required" to true))
        assertThat(config.codexThreadSandbox).isEqualTo("docker")
        assertThat(config.codexTurnSandboxPolicy).isEqualTo(mapOf("readonly" to true))
        assertThat(config.turnTimeoutMs).isEqualTo(1_800_000L)
        assertThat(config.readTimeoutMs).isEqualTo(10_000L)
        assertThat(config.stallTimeoutMs).isEqualTo(600_000L)
    }

    @Test
    fun `resolveEnvRef falls back to System getenv`() {
        val original = System.getenv("PATH")
        assertThat(original).isNotNull()
        val result = ServiceConfig.resolveEnvRef("\$PATH")
        assertThat(result).isEqualTo(original)
    }

    @Test
    fun `parseStages with invalid entries is filtered out`() {
        val config = ServiceConfig.fromMap(
            mapOf("agent" to mapOf(
                "kind" to "codex",
                "stages" to mapOf(
                    123 to mapOf("prompt" to "should-be-skipped"),
                    "Review" to "not-a-map",
                    "Todo" to mapOf("prompt" to "implement.md")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.stages.size).isEqualTo(1)
        assertThat(config.stages["todo"]?.prompt).isEqualTo("implement.md")
    }
}
