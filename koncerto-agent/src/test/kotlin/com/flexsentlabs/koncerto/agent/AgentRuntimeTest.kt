package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.lang.reflect.Method
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AgentRuntimeTest {

    private val logger = StructuredLogger(emptyList<LogSink>())
    private val workspacePath: Path = Path.of("/tmp")

    @Suppress("DEPRECATION")
    private fun buildFullCommand(
        factory: AgentRuntimeFactory,
        agentKind: String,
        command: String,
        model: String?,
        effort: String?
    ): String {
        val method: Method = AgentRuntimeFactory::class.java.getDeclaredMethod(
            "buildFullCommand", String::class.java, String::class.java, String::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(factory, agentKind, command, model, effort) as String
    }

    @Test
    fun `create codex returns CodexRuntime instance`() {
        val factory = AgentRuntimeFactory(logger)
        val runtime = factory.create("codex", "opencode", workspacePath)
        assertThat(runtime).isNotNull()
        assertThat(runtime is CodexRuntime).isTrue()
    }

    @Test
    fun `create opencode returns OpencodeRuntime instance`() {
        val factory = AgentRuntimeFactory(logger)
        val runtime = factory.create("opencode", "opencode", workspacePath)
        assertThat(runtime).isNotNull()
        assertThat(runtime is OpencodeRuntime).isTrue()
    }

    @Test
    fun `create claude returns ClaudeReviewRuntime instance`() {
        val factory = AgentRuntimeFactory(logger)
        val runtime = factory.create("claude", "claude", workspacePath)
        assertThat(runtime).isNotNull()
        assertThat(runtime is ClaudeReviewRuntime).isTrue()
    }

    @Test
    fun `create unknown kind throws IllegalArgumentException`() {
        val factory = AgentRuntimeFactory(logger)
        assertThrows<IllegalArgumentException> {
            factory.create("unknown", "cmd", workspacePath)
        }
    }

    @Test
    fun `factory with docker config enabled and container id returns DockerRuntime`() {
        val factory = AgentRuntimeFactory(logger)
        val dockerConfig = DockerConfig(enabled = true)
        val runtime = factory.create("codex", "echo hello", workspacePath, dockerConfig, "test-container")
        assertThat(runtime).isNotNull()
        assertThat(runtime is DockerRuntime).isTrue()
    }

    @Test
    fun `buildFullCommand appends model flag`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "codex", "opencode", "claude-sonnet-4-20250514", null)
        assertThat(result).isEqualTo("opencode --model claude-sonnet-4-20250514")
    }

    @Test
    fun `buildFullCommand appends codex effort flag`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "codex", "opencode", null, "high")
        assertThat(result).isEqualTo("opencode -c model_reasoning_effort=high")
    }

    @Test
    fun `buildFullCommand appends claude effort flag`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "claude", "claude", null, "high")
        assertThat(result).isEqualTo("claude --effort high")
    }

    @Test
    fun `buildFullCommand appends opencode effort flag`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "opencode", "opencode", null, "creative")
        assertThat(result).isEqualTo("opencode --variant creative")
    }

    @Test
    fun `buildFullCommand appends both model and effort for codex`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "codex", "opencode", "gpt-4o", "high")
        assertThat(result).isEqualTo("opencode --model gpt-4o -c model_reasoning_effort=high")
    }

    @Test
    fun `buildFullCommand appends both model and effort for claude`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "claude", "claude", "claude-sonnet-4", "high")
        assertThat(result).isEqualTo("claude --model claude-sonnet-4 --effort high")
    }

    @Test
    fun `buildFullCommand appends both model and effort for opencode`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "opencode", "opencode", "gpt-4o", "creative")
        assertThat(result).isEqualTo("opencode --model gpt-4o --variant creative")
    }

    @Test
    fun `buildFullCommand no model or effort returns command unchanged`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "codex", "opencode", null, null)
        assertThat(result).isEqualTo("opencode")
    }

    @Test
    fun `buildFullCommand unknown agent kind ignores effort`() {
        val factory = AgentRuntimeFactory(logger)
        val result = buildFullCommand(factory, "unknown-agent", "some-command", null, "high")
        assertThat(result).isEqualTo("some-command")
    }
}
