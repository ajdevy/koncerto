package com.anomaly.koncerto.core.config

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
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
}
