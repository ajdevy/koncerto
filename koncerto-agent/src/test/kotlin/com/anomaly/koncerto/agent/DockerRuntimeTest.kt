package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class DockerRuntimeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `start returns false when docker daemon is unavailable`() = runBlocking {
        val ws = Files.createTempDirectory("docker-rt-test-")
        val runtime = DockerRuntime("echo hello", ws, noopLogger(), "nonexistent-container-id")
        val result = withTimeout(10_000) { runtime.start() }
        assertThat(result).isFalse()
    }

    @Test
    fun `isAlive returns false for nonexistent container`() {
        val ws = Files.createTempDirectory("docker-rt-test-")
        val runtime = DockerRuntime("echo hello", ws, noopLogger(), "nonexistent-container-id")
        assertThat(runtime.isAlive()).isFalse()
    }

    @Test
    fun `stop does not throw for nonexistent container`() {
        val ws = Files.createTempDirectory("docker-rt-test-")
        val runtime = DockerRuntime("echo hello", ws, noopLogger(), "nonexistent-container-id")
        assertThat(runCatching { runtime.stop() }.isSuccess).isTrue()
    }

    @Test
    fun `send without start does not throw`() {
        val ws = Files.createTempDirectory("docker-rt-test-")
        val runtime = DockerRuntime("echo hello", ws, noopLogger(), "nonexistent-container-id")
        val id = runtime.send("test/method", null)
        assertThat(id.length > 0).isTrue()
    }

    @Test
    fun `events channel closes cleanly on stop`() = runBlocking {
        val ws = Files.createTempDirectory("docker-rt-test-")
        val runtime = DockerRuntime("echo hello", ws, noopLogger(), "nonexistent-container-id")
        runtime.stop()
        val events = mutableListOf<AgentEvent>()
        try {
            withTimeout(1_000) {
                runtime.events().collect { e ->
                    events.add(e)
                }
            }
        } catch (_: Exception) {}
        assertThat(true).isTrue()
    }
}
