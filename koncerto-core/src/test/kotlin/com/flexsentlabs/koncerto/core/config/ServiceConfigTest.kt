package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class ServiceConfigTest {

    private fun ServiceConfig.project(): ProjectConfig =
        projects["default"] ?: throw IllegalStateException("missing default project")

    @Test
    fun `defaults are applied when fields are missing`() {
        val config = ServiceConfig(
            projects = mapOf("default" to ProjectConfig(
                tracker = TrackerConfig(kind = "linear", endpoint = "https://api.linear.app/graphql", apiKey = "", projectSlug = ""),
                workspace = WorkspaceConfig(root = "/tmp/test"),
                agent = AgentProjectConfig()
            ))
        )

        assertThat(config.pollIntervalMs).isEqualTo(30_000L)
        assertThat(config.project().tracker.activeStates).containsExactly("Todo", "In Progress")
        assertThat(config.project().tracker.terminalStates).containsExactly("Closed", "Cancelled", "Canceled", "Duplicate", "Done")
        assertThat(config.project().tracker.requiredLabels).isEqualTo(emptyList())
        assertThat(config.project().agent.maxConcurrentAgents).isEqualTo(2)
        assertThat(config.project().agent.maxTurns).isEqualTo(20)
        assertThat(config.project().agent.maxRetryBackoffMs).isEqualTo(300_000L)
        assertThat(config.project().agent.turnTimeoutMs).isEqualTo(3_600_000L)
        assertThat(config.project().agent.readTimeoutMs).isEqualTo(5_000L)
        assertThat(config.project().agent.stallTimeoutMs).isEqualTo(300_000L)
        assertThat(config.hooksTimeoutMs()).isEqualTo(60_000L)
        assertThat(config.project().agent.kind).isEqualTo("opencode")
        assertThat(config.project().agent.command).isNull()
        assertThat(config.gitConfig.enabled).isEqualTo(false)
        assertThat(config.gitConfig.branchPrefix).isEqualTo("feature/")
        assertThat(config.gitConfig.autoCommit).isEqualTo(true)
        assertThat(config.gitConfig.autoPush).isEqualTo(false)
        assertThat(config.gitConfig.createPr).isEqualTo(false)
        assertThat(config.gitConfig.prBase).isEqualTo("main")
        assertThat(config.project().tracker.blockedState).isEqualTo("Blocked")
        assertThat(config.project().tracker.projectAdmin).isNull()
    }

    @Test
    fun `agent kind defaults to opencode when not specified`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.kind).isEqualTo("opencode")
    }

    @Test
    fun `agent kind codex is accepted`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("kind" to "codex")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.kind).isEqualTo("codex")
    }

    @Test
    fun `agent kind opencode is accepted`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("kind" to "opencode", "command" to "opencode-my-build")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.kind).isEqualTo("opencode")
        assertThat(config.project().agent.command).isEqualTo("opencode-my-build")
    }

    @Test
    fun `opencode command defaults to null`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("kind" to "opencode")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.command).isNull()
    }

    @Test
    fun `tracker kind is required and validated`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("project_slug" to "p", "api_key" to "k"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `tracker api_key is required`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        val ex = result.exceptionOrNull()
        assertThat(ex).isNotNull()
        assertThat(ex!!.message ?: "").contains("api_key")
    }

    @Test
    fun `tracker api_key rejects empty string`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        val ex = result.exceptionOrNull()
        assertThat(ex).isNotNull()
        assertThat(ex!!.message ?: "").contains("api_key")
    }

    @Test
    fun `linear api_key resolved from env var`() {
        try {
            System.setProperty("LINEAR_API_KEY_FOR_TEST", "secret")
            val config = ServiceConfig.fromMap(
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf(
                            "kind" to "linear",
                            "api_key" to "\$LINEAR_API_KEY_FOR_TEST",
                            "project_slug" to "proj"
                        ),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )),
                workflowFileDir = "/tmp"
            )
            assertThat(config.project().tracker.apiKey).isEqualTo("secret")
        } finally {
            System.clearProperty("LINEAR_API_KEY_FOR_TEST")
        }
    }

    @Test
    fun `workspace root expands tilde`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "~/workspaces"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().workspace.root).isEqualTo("${System.getProperty("user.home")}/workspaces")
    }

    @Test
    fun `relative workspace root resolves against workflow file dir`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "ws"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/some/dir"
        )
        assertThat(config.project().workspace.root).isEqualTo("/some/dir/ws")
    }

    @Test
    fun `stages defaults to empty when not specified`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages).isEqualTo(emptyMap())
    }

    @Test
    fun `parse single stage with all fields`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "command" to "opencode",
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
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val todo = config.project().agent.stages["todo"]
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
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "stages" to mapOf(
                            "Todo" to mapOf("prompt" to "impl.md", "max_concurrent" to 3),
                            "In Review" to mapOf("prompt" to "review.md", "max_concurrent" to 1)
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages.keys).isEqualTo(setOf("todo", "in review"))
        assertThat(config.project().agent.stages["todo"]?.prompt).isEqualTo("impl.md")
        assertThat(config.project().agent.stages["in review"]?.prompt).isEqualTo("review.md")
    }

    @Test
    fun `stage with partial fields gets nulls for missing`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "stages" to mapOf(
                            "Todo" to mapOf("prompt" to "only-prompt.md")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val todo = config.project().agent.stages["todo"]
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
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().tracker.blockedState).isEqualTo("Blocked")
    }

    @Test
    fun `blockedState parsed from tracker section`() {
        System.setProperty("KONCERTO_TEST_KEY", "k")
        try {
            val config = ServiceConfig.fromMap(
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf(
                            "kind" to "linear", "project_slug" to "p",
                            "api_key" to "\$KONCERTO_TEST_KEY", "blocked_state" to "Waiting"
                        ),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )),
                workflowFileDir = "/tmp"
            )
            assertThat(config.project().tracker.blockedState).isEqualTo("Waiting")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `projectAdmin defaults to null`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().tracker.projectAdmin).isNull()
    }

    @Test
    fun `projectAdmin parsed from tracker section`() {
        System.setProperty("KONCERTO_TEST_KEY", "k")
        try {
            val config = ServiceConfig.fromMap(
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf(
                            "kind" to "linear", "project_slug" to "p",
                            "api_key" to "\$KONCERTO_TEST_KEY", "project_admin" to "user-1"
                        ),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )),
                workflowFileDir = "/tmp"
            )
            assertThat(config.project().tracker.projectAdmin).isEqualTo("user-1")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `fromMap throws on invalid config`() {
        val ex = org.junit.jupiter.api.assertThrows<IllegalStateException> {
            ServiceConfig.fromMap(
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf("kind" to "", "project_slug" to "p", "api_key" to "k"),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )),
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
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf(
                            "kind" to "linear",
                            "api_key" to "\$KONCERTO_TEST_KEY",
                            "project_slug" to "p",
                            "required_labels" to listOf(" bugfix ", " feature "),
                            "active_states" to listOf("Active", "Review"),
                            "terminal_states" to listOf("Done", "Wontfix"),
                            "blocked_state" to "Waiting",
                            "project_admin" to "admin-1"
                        ),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )),
                workflowFileDir = "/tmp"
            )
            assertThat(config.project().tracker.requiredLabels).isEqualTo(listOf("bugfix", "feature"))
            assertThat(config.project().tracker.activeStates).isEqualTo(listOf("Active", "Review"))
            assertThat(config.project().tracker.terminalStates).isEqualTo(listOf("Done", "Wontfix"))
            assertThat(config.project().tracker.blockedState).isEqualTo("Waiting")
            assertThat(config.project().tracker.projectAdmin).isEqualTo("admin-1")
        } finally {
            System.clearProperty("KONCERTO_TEST_KEY")
        }
    }

    @Test
    fun `parsePollingInterval uses explicit value`() {
        val config = ServiceConfig.fromMap(
            mapOf("poll_interval_ms" to 5000),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(5000L)
    }

    @Test
    fun `parseAgentSection maxTurns zero throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("max_turns" to 0)
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `parseAgentSection perState with valid entries`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "max_concurrent_agents_by_state" to mapOf(
                            "Todo" to 3,
                            "In Review" to 2
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.maxConcurrentAgentsByState["todo"]).isEqualTo(3)
        assertThat(config.project().agent.maxConcurrentAgentsByState["in review"]).isEqualTo(2)
    }

    @Test
    fun `parseAgentSection perState filters invalid entries`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "max_concurrent_agents_by_state" to mapOf(
                            123 to 3,
                            "Review" to "not-a-number",
                            "Blocked" to 0,
                            "Todo" to 2
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.maxConcurrentAgentsByState.size).isEqualTo(1)
        assertThat(config.project().agent.maxConcurrentAgentsByState["todo"]).isEqualTo(2)
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
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "stages" to mapOf(
                            123 to mapOf("prompt" to "should-be-skipped"),
                            "Review" to "not-a-map",
                            "Todo" to mapOf("prompt" to "implement.md")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages.size).isEqualTo(1)
        assertThat(config.project().agent.stages["todo"]?.prompt).isEqualTo("implement.md")
    }

    @Test
    fun `multiple projects parsed`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "frontend" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k1", "project_slug" to "frontend-app"),
                    "workspace" to mapOf("root" to "/tmp/frontend"),
                    "agent" to mapOf("kind" to "opencode")
                ),
                "backend" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k2", "project_slug" to "backend-app"),
                    "workspace" to mapOf("root" to "/tmp/backend"),
                    "agent" to mapOf("kind" to "codex")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.projects.size).isEqualTo(2)
        assertThat(config.projects["frontend"]?.tracker?.projectSlug).isEqualTo("frontend-app")
        assertThat(config.projects["frontend"]?.agent?.kind).isEqualTo("opencode")
        assertThat(config.projects["frontend"]?.workspace?.root).isEqualTo("/tmp/frontend")
        assertThat(config.projects["backend"]?.tracker?.projectSlug).isEqualTo("backend-app")
        assertThat(config.projects["backend"]?.agent?.kind).isEqualTo("codex")
        assertThat(config.projects["backend"]?.workspace?.root).isEqualTo("/tmp/backend")
    }

    @Test
    fun `poll_interval_ms at top level`() {
        val config = ServiceConfig.fromMap(
            mapOf("poll_interval_ms" to 15000),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(15000L)
    }

    @Test
    fun `polling interval_ms legacy format emits deprecation warning`() {
        val config = ServiceConfig.fromMap(
            mapOf("polling" to mapOf("interval_ms" to 20000)),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(20000L)
        assertThat(config.deprecationWarnings).contains(
            "polling.interval_ms is deprecated; use poll_interval_ms at the top level"
        )
    }

    @Test
    fun `poll_interval_ms modern format has no deprecation warnings`() {
        val config = ServiceConfig.fromMap(
            mapOf("poll_interval_ms" to 15000),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(15000L)
        assertThat(config.deprecationWarnings).isEqualTo(emptyList())
    }

    @Test
    fun `parse agent providers from config`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "agents" to mapOf(
                            "fast" to mapOf("kind" to "codex", "model" to "claude-sonnet-4"),
                            "thorough" to mapOf("kind" to "opencode", "command" to "opencode-custom")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.agents.size).isEqualTo(2)
        assertThat(config.project().agent.agents["fast"]?.kind).isEqualTo("codex")
        assertThat(config.project().agent.agents["fast"]?.model).isEqualTo("claude-sonnet-4")
        assertThat(config.project().agent.agents["thorough"]?.kind).isEqualTo("opencode")
        assertThat(config.project().agent.agents["thorough"]?.command).isEqualTo("opencode-custom")
        assertThat(config.project().agent.agents["thorough"]?.model).isNull()
    }

    @Test
    fun `parse stage agent reference`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "stages" to mapOf(
                            "implement" to mapOf("agent" to "fast", "prompt" to "do it")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages["implement"]?.agent).isEqualTo("fast")
    }

    @Test
    fun `parse empty agents defaults to empty map`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("kind" to "opencode")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.agents).isEqualTo(emptyMap())
    }

    @Test
    fun `parse stage without agent reference defaults null`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "stages" to mapOf("implement" to mapOf("prompt" to "do it"))
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages["implement"]?.agent).isNull()
    }

    @Test
    fun `parse agent providers with missing kind is skipped`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "agents" to mapOf(
                            "good" to mapOf("kind" to "codex"),
                            "no-kind" to mapOf("command" to "run")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.agents.size).isEqualTo(1)
        assertThat(config.project().agent.agents["good"]?.kind).isEqualTo("codex")
        assertThat(config.project().agent.agents["no-kind"]).isNull()
    }

    @Test
    fun `parse agent providers skips entries with non-string keys`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "agents" to mapOf(
                            123 to mapOf("kind" to "codex"),
                            "valid" to mapOf("kind" to "opencode")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.agents.size).isEqualTo(1)
        assertThat(config.project().agent.agents["valid"]?.kind).isEqualTo("opencode")
    }

    @Test
    fun `parseAgentConfig with routing rules`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "routing_rules" to listOf(
                            mapOf(
                                "if_label" to "frontend",
                                "use_agent" to "frontend-agent",
                                "priority" to 10
                            ),
                            mapOf(
                                "if_label_prefix" to "backend:",
                                "use_agent" to "backend-agent",
                                "priority" to 5
                            ),
                            mapOf(
                                "if_state" to "bug",
                                "if_priority_max" to 2,
                                "use_agent" to "frontend-agent",
                                "priority" to 8
                            )
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val rules = config.project().agent.routingRules
        assertThat(rules.size).isEqualTo(3)
        assertThat(rules[0].ifLabel).isEqualTo("frontend")
        assertThat(rules[1].ifState).isEqualTo("bug")
        assertThat(rules[1].ifPriorityMax).isEqualTo(2)
        assertThat(rules[1].useAgent).isEqualTo("frontend-agent")
        assertThat(rules[2].ifLabelPrefix).isEqualTo("backend:")
    }

    @Test
    fun `parseAgentConfig routing rules sorted by priority descending`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "kind" to "opencode",
                        "routing_rules" to listOf(
                            mapOf("use_agent" to "a", "priority" to 1),
                            mapOf("use_agent" to "b", "priority" to 10),
                            mapOf("use_agent" to "c", "priority" to 5)
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.routingRules.map { it.useAgent }).isEqualTo(listOf("b", "c", "a"))
    }

    @Test
    fun `parseAgentConfig empty routing rules defaults to empty list`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf("kind" to "opencode")
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.routingRules).isEqualTo(emptyList())
    }

    @Test
    fun `parse stage with followUp config`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "stages" to mapOf(
                            "In Progress" to mapOf(
                                "prompt" to "Do work",
                                "on_complete_state" to "Done",
                                "follow_up" to mapOf(
                                    "title_template" to "PR Review: {{ issue.title }}",
                                    "state" to "Todo",
                                    "labels" to listOf("pr-review", "auto"),
                                    "link_type" to "blocks",
                                    "assignee" to "creator"
                                )
                            )
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val stage = config.project().agent.stages["in progress"]
        assertThat(stage).isNotNull()
        assertThat(stage!!.followUp).isNotNull()
        assertThat(stage.followUp!!.titleTemplate).isEqualTo("PR Review: {{ issue.title }}")
        assertThat(stage.followUp!!.state).isEqualTo("Todo")
        assertThat(stage.followUp!!.labels).isEqualTo(listOf("pr-review", "auto"))
        assertThat(stage.followUp!!.linkType).isEqualTo("blocks")
        assertThat(stage.followUp!!.assignee).isEqualTo("creator")
    }

    @Test
    fun `parse stage without followUp`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "stages" to mapOf(
                            "In Progress" to mapOf(
                                "prompt" to "Do work",
                                "on_complete_state" to "Done"
                            )
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val stage = config.project().agent.stages["in progress"]
        assertThat(stage).isNotNull()
        assertThat(stage!!.followUp).isNull()
    }

    @Test
    fun `docker config defaults when not specified`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.docker).isNull()
    }

    @Test
    fun `docker config parses all fields`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "docker" to mapOf(
                            "enabled" to true,
                            "image" to "my-agent:v2",
                            "cpu" to "2.0",
                            "memory" to "2g",
                            "network" to false,
                            "dockerfile" to "CustomDockerfile"
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val docker = config.project().agent.docker
        assertThat(docker).isNotNull()
        assertThat(docker!!.enabled).isEqualTo(true)
        assertThat(docker.image).isEqualTo("my-agent:v2")
        assertThat(docker.cpu).isEqualTo("2.0")
        assertThat(docker.memory).isEqualTo("2g")
        assertThat(docker.network).isEqualTo(false)
        assertThat(docker.dockerfile).isEqualTo("CustomDockerfile")
    }

    @Test
    fun `docker config partial fields get defaults`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "docker" to mapOf("enabled" to false)
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val docker = config.project().agent.docker
        assertThat(docker).isNotNull()
        assertThat(docker!!.enabled).isEqualTo(false)
        assertThat(docker.image).isEqualTo("koncerto-agent:latest")
        assertThat(docker.cpu).isEqualTo("auto")
        assertThat(docker.memory).isEqualTo("auto")
        assertThat(docker.network).isEqualTo(true)
        assertThat(docker.dockerfile).isEqualTo("Dockerfile.agent")
    }

    @Test
    fun `docker config disabled`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "docker" to mapOf("enabled" to false)
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.docker!!.enabled).isEqualTo(false)
    }

    @Test
    fun `rate limiter config parsed`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(),
                    "rate_limiter" to mapOf(
                        "requests_per_second" to 5,
                        "max_burst" to 10
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val rl = config.project().rateLimiter
        assertThat(rl).isNotNull()
        assertThat(rl!!.requestsPerSecond).isEqualTo(5)
        assertThat(rl.maxBurst).isEqualTo(10)
    }

    @Test
    fun `circuit breaker config parsed`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(),
                    "circuit_breaker" to mapOf(
                        "failure_threshold" to 3,
                        "reset_timeout_ms" to 15000
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val cb = config.project().circuitBreaker
        assertThat(cb).isNotNull()
        assertThat(cb!!.failureThreshold).isEqualTo(3)
        assertThat(cb.resetTimeoutMs).isEqualTo(15000)
    }

    @Test
    fun `notifications config with all channels`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(),
                    "notifications" to mapOf(
                        "on_completed" to true,
                        "on_failed" to true,
                        "on_stalled" to false,
                        "on_clarification" to true,
                        "on_limit" to listOf("linear"),
                        "limit_cooldown_ms" to 600000,
                        "telegram" to mapOf(
                            "bot_token" to "tg_bot_token",
                            "chat_id" to "-100123"
                        ),
                        "email" to mapOf(
                            "smtp_host" to "smtp.example.com",
                            "smtp_port" to 587,
                            "username" to "user",
                            "password" to "smtp_pass",
                            "from" to "bot@example.com",
                            "to" to "admin@example.com"
                        ),
                        "webhook" to mapOf(
                            "url" to "https://hooks.example.com/alert",
                            "headers" to mapOf(
                                "Authorization" to "wh_secret"
                            )
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val n = config.project().notifications
        assertThat(n.onCompleted).isEqualTo(true)
        assertThat(n.onFailed).isEqualTo(true)
        assertThat(n.onStalled).isEqualTo(false)
        assertThat(n.onClarification).isEqualTo(true)
        assertThat(n.onLimit).containsExactly("linear")
        assertThat(n.limitCooldownMs).isEqualTo(600000)
        assertThat(n.telegram).isNotNull()
        assertThat(n.telegram!!.botToken).isEqualTo("tg_bot_token")
        assertThat(n.telegram.chatId).isEqualTo("-100123")
        assertThat(n.email).isNotNull()
        assertThat(n.email!!.smtpHost).isEqualTo("smtp.example.com")
        assertThat(n.email.smtpPort).isEqualTo(587)
        assertThat(n.email.username).isEqualTo("user")
        assertThat(n.email.from).isEqualTo("bot@example.com")
        assertThat(n.email.to).isEqualTo("admin@example.com")
        assertThat(n.webhook).isNotNull()
        assertThat(n.webhook!!.url).isEqualTo("https://hooks.example.com/alert")
    }

    @Test
    fun `notifications defaults when not specified`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        val n = config.project().notifications
        assertThat(n.onCompleted).isEqualTo(false)
        assertThat(n.onFailed).isEqualTo(false)
        assertThat(n.telegram).isNull()
        assertThat(n.email).isNull()
        assertThat(n.webhook).isNull()
    }

    @Test
    fun `notifications infers true when channels present`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(),
                    "notifications" to mapOf(
                        "telegram" to mapOf("bot_token" to "t", "chat_id" to "c")
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val n = config.project().notifications
        assertThat(n.onCompleted).isEqualTo(true)
        assertThat(n.onFailed).isEqualTo(true)
    }

    @Test
    fun `demo recording config parsed`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                    "demo_recording" to mapOf(
                        "enabled" to true,
                        "trigger" to "review_passed",
                        "target_url" to "http://localhost:3000",
                        "cleanup_interval_hours" to 48,
                    "platform" to mapOf("web" to "playwright", "terminal" to "asciinema"),
                    "quality" to mapOf("resolution" to "1920x1080", "fps" to 15, "codec" to "h264"),
                    "storage" to mapOf(
                        "r2_endpoint" to "https://r2.example.com",
                        "r2_bucket" to "my-bucket",
                        "r2_access_key" to "ak",
                        "r2_secret_key" to "sk",
                        "public_url_base" to "https://pub.example.com",
                        "presigned_url_ttl" to 86400,
                        "region" to "us-east-1"
                    ),
                    "ai" to mapOf("model" to "claude", "timeline" to true, "repro_steps" to true),
                    "retry" to mapOf("max_attempts" to 5, "backoff" to "linear"),
                    "error" to mapOf("on_failure" to "retry")
                ),
                "projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )
            ),
            workflowFileDir = "/tmp"
        )
        val dr = config.demoRecording
        assertThat(dr.enabled).isEqualTo(true)
        assertThat(dr.trigger).isEqualTo("review_passed")
        assertThat(dr.targetUrl).isEqualTo("http://localhost:3000")
        assertThat(dr.cleanupIntervalHours).isEqualTo(48)
        assertThat(dr.platform.web).isEqualTo("playwright")
        assertThat(dr.platform.terminal).isEqualTo("asciinema")
        assertThat(dr.quality.resolution).isEqualTo("1920x1080")
        assertThat(dr.quality.fps).isEqualTo(15)
        assertThat(dr.quality.codec).isEqualTo("h264")
        assertThat(dr.storage).isNotNull()
        assertThat(dr.storage!!.r2Endpoint).isEqualTo("https://r2.example.com")
        assertThat(dr.storage.r2Bucket).isEqualTo("my-bucket")
        assertThat(dr.storage.r2AccessKey).isEqualTo("ak")
        assertThat(dr.storage.region).isEqualTo("us-east-1")
        assertThat(dr.storage.presignedUrlTtl).isEqualTo(86400)
        assertThat(dr.ai.model).isEqualTo("claude")
        assertThat(dr.ai.timeline).isEqualTo(true)
        assertThat(dr.retry.maxAttempts).isEqualTo(5)
        assertThat(dr.retry.backoff).isEqualTo("linear")
        assertThat(dr.error.onFailure).isEqualTo("retry")
    }

    @Test
    fun `demo recording defaults when not specified`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.demoRecording.enabled).isEqualTo(false)
        assertThat(config.demoRecording.trigger).isEqualTo("review_passed")
        assertThat(config.demoRecording.quality.resolution).isEqualTo("1280x720")
    }

    @Test
    fun `hooks config accessed from service config`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.hooks.timeoutMs).isEqualTo(60000)
    }

    @Test
    fun `admin api key accessed from service config`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "admin" to mapOf("apiKey" to "secret-123"),
                "projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )
            ),
            workflowFileDir = "/tmp"
        )
        assertThat(config.adminApiKey).isEqualTo("secret-123")
    }

    @Test
    fun `resolveInlineEnvRefs replaces dollar and brace env references`() {
        try {
            System.setProperty("INLINE_TEST_VAR", "resolved-value")
            assertThat(ServiceConfig.resolveInlineEnvRefs("prefix-\$INLINE_TEST_VAR-suffix"))
                .isEqualTo("prefix-resolved-value-suffix")
            assertThat(ServiceConfig.resolveInlineEnvRefs("use \${INLINE_TEST_VAR} here"))
                .isEqualTo("use resolved-value here")
        } finally {
            System.clearProperty("INLINE_TEST_VAR")
        }
    }

    @Test
    fun `resolveInlineEnvRefs leaves unknown refs unchanged`() {
        assertThat(ServiceConfig.resolveInlineEnvRefs("no refs here")).isEqualTo("no refs here")
        assertThat(ServiceConfig.resolveInlineEnvRefs("missing \$UNKNOWN_INLINE_VAR_XYZ"))
            .isEqualTo("missing \$UNKNOWN_INLINE_VAR_XYZ")
    }

    @Test
    fun `resolveInlineEnvRefs returns null for null input`() {
        assertThat(ServiceConfig.resolveInlineEnvRefs(null)).isNull()
    }

    @Test
    fun `parse stage on_failure_state and max_review_attempts`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "stages" to mapOf(
                            "In Review" to mapOf(
                                "prompt" to "review.md",
                                "on_failure_state" to "Blocked",
                                "max_review_attempts" to 5
                            )
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val stage = config.project().agent.stages["in review"]
        assertThat(stage).isNotNull()
        assertThat(stage!!.onFailureState).isEqualTo("Blocked")
        assertThat(stage.maxReviewAttempts).isEqualTo(5)
    }

    @Test
    fun `parse stage and agent effort`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "agents" to mapOf(
                            "fast" to mapOf("kind" to "codex", "effort" to "high")
                        ),
                        "stages" to mapOf(
                            "Todo" to mapOf("prompt" to "impl.md", "effort" to "medium")
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(config.project().agent.stages["todo"]?.effort).isEqualTo("medium")
        assertThat(config.project().agent.agents["fast"]?.effort).isEqualTo("high")
    }

    @Test
    fun `quality config dimensions parsed from resolution`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "demo_recording" to mapOf(
                    "quality" to mapOf("resolution" to "1920x1080", "fps" to 24, "codec" to "h264")
                ),
                "projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf()
                    )
                )
            ),
            workflowFileDir = "/tmp"
        )
        val quality = config.demoRecording.quality
        assertThat(quality.resolution).isEqualTo("1920x1080")
        assertThat(quality.width).isEqualTo(1920)
        assertThat(quality.height).isEqualTo(1080)
        assertThat(quality.fps).isEqualTo(24)
        assertThat(quality.codec).isEqualTo("h264")
    }

    @Test
    fun `quality config dimensions fall back when resolution invalid`() {
        val quality = DemoRecordingConfig.QualityConfig(resolution = "invalid")
        assertThat(quality.width).isEqualTo(1280)
        assertThat(quality.height).isEqualTo(720)
    }

    @Test
    fun `resolvePath expands tilde and relative paths`() {
        val absolute = ServiceConfig.resolvePath("/tmp/app", "/workflow")
        assertThat(absolute).isEqualTo(java.nio.file.Paths.get("/tmp/app"))
        val relative = ServiceConfig.resolvePath("subdir/app", "/workflow")
        assertThat(relative).isEqualTo(java.nio.file.Paths.get("/workflow/subdir/app"))
        val tilde = ServiceConfig.resolvePath("~/projects/app", "/workflow")
        assertThat(tilde.toString()).contains("projects/app")
    }

    @Test
    fun `parseLimitPauseConfig parses all fields from agent map`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(
                        "limit_pause" to mapOf(
                            "enabled" to false,
                            "claude_default_resume_ms" to 120_000L,
                            "codex_default_resume_ms" to 90_000L,
                            "linear_comments" to false
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val pause = config.project().agent.limitPause
        assertThat(pause.enabled).isEqualTo(false)
        assertThat(pause.claudeDefaultResumeMs).isEqualTo(120_000L)
        assertThat(pause.codexDefaultResumeMs).isEqualTo(90_000L)
        assertThat(pause.linearComments).isEqualTo(false)
    }

    @Test
    fun `parseLimitPauseConfig uses defaults when map is null`() {
        val pause = ServiceConfig.parseLimitPauseConfig(null)
        assertThat(pause.enabled).isEqualTo(true)
        assertThat(pause.claudeDefaultResumeMs).isEqualTo(LimitPauseConfig.DEFAULT_RESUME_MS)
        assertThat(pause.codexDefaultResumeMs).isEqualTo(LimitPauseConfig.DEFAULT_RESUME_MS)
        assertThat(pause.linearComments).isEqualTo(true)
    }

    @Test
    fun `tracker kind blank throws from parseTrackerConfig`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        assertThat(result.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `tracker project_slug blank throws`() {
        val result = ServiceConfig.fromMapOrError(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to ""),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf()
                )
            )),
            workflowFileDir = "/tmp"
        )
        val ex = result.exceptionOrNull()
        assertThat(ex).isNotNull()
        assertThat(ex!!.message ?: "").contains("project_slug")
    }

    @Test
    fun `webhook notification headers resolve env refs`() {
        System.setProperty("WEBHOOK_AUTH_FOR_TEST", "Bearer secret")
        try {
            val config = ServiceConfig.fromMap(
                mapOf("projects" to mapOf(
                    "default" to mapOf(
                        "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                        "workspace" to mapOf("root" to "/tmp/test"),
                        "agent" to mapOf(),
                        "notifications" to mapOf(
                            "webhook" to mapOf(
                                "url" to "https://hooks.example.com/alert",
                                "headers" to mapOf("Authorization" to "\$WEBHOOK_AUTH_FOR_TEST")
                            )
                        )
                    )
                )),
                workflowFileDir = "/tmp"
            )
            assertThat(config.project().notifications.webhook?.headers?.get("Authorization"))
                .isEqualTo("Bearer secret")
        } finally {
            System.clearProperty("WEBHOOK_AUTH_FOR_TEST")
        }
    }

    @Test
    fun `parseLimitPauseConfig partial map uses defaults for missing fields`() {
        val pause = ServiceConfig.parseLimitPauseConfig(mapOf("enabled" to true))
        assertThat(pause.enabled).isEqualTo(true)
        assertThat(pause.claudeDefaultResumeMs).isEqualTo(LimitPauseConfig.DEFAULT_RESUME_MS)
        assertThat(pause.codexDefaultResumeMs).isEqualTo(LimitPauseConfig.DEFAULT_RESUME_MS)
        assertThat(pause.linearComments).isEqualTo(true)
    }

    @Test
    fun `email notification config parses optional credentials`() {
        val config = ServiceConfig.fromMap(
            mapOf("projects" to mapOf(
                "default" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                    "workspace" to mapOf("root" to "/tmp/test"),
                    "agent" to mapOf(),
                    "notifications" to mapOf(
                        "email" to mapOf(
                            "smtp_host" to "smtp.example.com",
                            "from" to "bot@example.com",
                            "to" to "admin@example.com"
                        )
                    )
                )
            )),
            workflowFileDir = "/tmp"
        )
        val email = config.project().notifications.email
        assertThat(email).isNotNull()
        assertThat(email!!.username).isNull()
        assertThat(email.password).isNull()
        assertThat(email.smtpPort).isEqualTo(587)
    }

    @Test
    fun `parseDemoRecording resolves storage env refs from workflow map`() {
        System.setProperty("TEST_R2_BUCKET", "resolved-bucket")
        try {
            val config = ServiceConfig.fromMap(
                mapOf(
                    "demo_recording" to mapOf(
                        "enabled" to true,
                        "storage" to mapOf(
                            "r2_bucket" to "\$TEST_R2_BUCKET",
                            "r2_endpoint" to "https://r2.example.com",
                            "r2_access_key" to "key",
                            "r2_secret_key" to "secret"
                        )
                    ),
                    "projects" to mapOf(
                        "default" to mapOf(
                            "tracker" to mapOf("kind" to "linear", "api_key" to "k", "project_slug" to "p"),
                            "workspace" to mapOf("root" to "/tmp/test"),
                            "agent" to mapOf()
                        )
                    )
                ),
                workflowFileDir = "/tmp"
            )
            assertThat(config.demoRecording.storage?.r2Bucket).isEqualTo("resolved-bucket")
        } finally {
            System.clearProperty("TEST_R2_BUCKET")
        }
    }
}
