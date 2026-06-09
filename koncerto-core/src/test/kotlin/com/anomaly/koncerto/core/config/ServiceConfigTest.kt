package com.anomaly.koncerto.core.config

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
    fun `polling interval_ms legacy format`() {
        val config = ServiceConfig.fromMap(
            mapOf("polling" to mapOf("interval_ms" to 20000)),
            workflowFileDir = "/tmp"
        )
        assertThat(config.pollIntervalMs).isEqualTo(20000L)
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
}
