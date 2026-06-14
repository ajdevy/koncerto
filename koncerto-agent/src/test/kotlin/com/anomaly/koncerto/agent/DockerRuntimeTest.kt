package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DockerRuntimeTest {

    @TempDir
    lateinit var tempDir: Path

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun testRuntime(containerId: String = "nonexistent-container-id") =
        DockerRuntime("echo hello", tempDir, noopLogger(), containerId)

    @Test
    fun `start returns false when docker daemon is unavailable`() = runBlocking {
        val runtime = testRuntime()
        val result = withTimeout(10_000) { runtime.start() }
        assertThat(result).isFalse()
    }

    @Test
    fun `isAlive returns false for nonexistent container`() {
        assertThat(testRuntime().isAlive()).isFalse()
    }

    @Test
    fun `stop does not throw for nonexistent container`() {
        assertThat(runCatching { testRuntime().stop() }.isSuccess).isTrue()
    }

    @Test
    fun `send without start does not throw`() {
        val id = testRuntime().send("test/method", null)
        assertThat(id).isNotEmpty()
    }

    @Test
    fun `events channel closes cleanly on stop`() = runBlocking {
        val runtime = testRuntime()
        runtime.stop()
        val events = mutableListOf<AgentEvent>()
        withTimeout(1_000) {
            runtime.events().collect { e ->
                events.add(e)
            }
        }
        assertThat(events).isEmpty()
    }
}
