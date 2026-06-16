package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AgentRuntimeFactoryTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `create codex runtime returns CodexRuntime instance`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val runtime = factory.create("codex", "echo hello", ws)
        assertThat(runtime).isNotNull()
        assertThat(runtime is CodexRuntime).isTrue()
    }

    @Test
    fun `create opencode runtime returns OpencodeRuntime instance`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val runtime = factory.create("opencode", "opencode", ws)
        assertThat(runtime).isNotNull()
        assertThat(runtime is OpencodeRuntime).isTrue()
    }

    @Test
    fun `create codex runtime starts successfully`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val runtime = factory.create("codex", "sleep 0.1", ws)
        val started = runBlocking { runtime.start() }
        assertThat(started).isTrue()
        runtime.stop()
    }

    @Test
    fun `create opencode runtime starts successfully`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val runtime = factory.create("opencode", "sleep 0.1", ws)
        val started = runBlocking { runtime.start() }
        assertThat(started).isTrue()
        runtime.stop()
    }

    @Test
    fun `create with invalid kind throws`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        try {
            factory.create("invalid-kind", "cmd", ws)
            throw AssertionError("expected exception")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `create with docker config and container id returns DockerRuntime`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val dockerConfig = DockerConfig(enabled = true)
        val runtime = factory.create("codex", "echo hello", ws, dockerConfig, "test-container-id")
        assertThat(runtime).isNotNull()
        assertThat(runtime is DockerRuntime).isTrue()
    }

    @Test
    fun `create with docker config but no container id returns non-Docker runtime`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val dockerConfig = DockerConfig(enabled = true)
        val runtime = factory.create("codex", "echo hello", ws, dockerConfig, null)
        assertThat(runtime).isNotNull()
        assertThat(runtime is CodexRuntime).isTrue()
    }

    @Test
    fun `create with docker disabled returns non-Docker runtime`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val dockerConfig = DockerConfig(enabled = false)
        val runtime = factory.create("codex", "echo hello", ws, dockerConfig, "test-container-id")
        assertThat(runtime).isNotNull()
        assertThat(runtime is CodexRuntime).isTrue()
    }

    @Test
    fun `create with null docker config returns non-Docker runtime`() {
        val factory = AgentRuntimeFactory(noopLogger())
        val ws = Files.createTempDirectory("factory-test-")
        val runtime = factory.create("opencode", "echo hello", ws, null, null)
        assertThat(runtime).isNotNull()
        assertThat(runtime is OpencodeRuntime).isTrue()
    }
}
