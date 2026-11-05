package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class DockerStreamTerminationTest {
    private fun logger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun terminalEvent(output: String): AgentEvent = runBlocking {
        val root = Files.createTempDirectory("docker-stream-test-")
        val fake = root.resolve("docker")
        Files.writeString(fake, "#!/bin/sh\ncase \"\u00241\" in\ninfo) exit 0;;\nexec) printf '%b' '$output'; exit 0;;\n*) exit 0;;\nesac\n")
        fake.toFile().setExecutable(true)
        DockerRuntime.testDockerOverride.set(fake.toString())
        val runtime = DockerRuntime("codex exec --json", root, logger(), "test-id")
        try {
            check(runtime.start())
            withTimeout(5000) {
                runtime.events().first { it is AgentEvent.TurnFailed || it is AgentEvent.TurnCompleted }
            }
        } finally {
            runtime.stop()
            DockerRuntime.testDockerOverride.set(null)
            root.toFile().deleteRecursively()
        }
    }

    @Test fun `truncated jsonl is a failure`() {
        val result = terminalEvent("{\"type\":\"thread.started\",\"thread_id\":\"t1\"}\\n")
        assertThat(result is AgentEvent.TurnFailed).isEqualTo(true)
    }

    @Test fun `empty codex output is a failure`() {
        assertThat(terminalEvent("") is AgentEvent.TurnFailed).isEqualTo(true)
    }

    @Test fun `explicit error is a failure`() {
        val result = terminalEvent("{\"type\":\"error\",\"message\":\"failed\"}\\n")
        assertThat(result is AgentEvent.TurnFailed).isEqualTo(true)
    }

    @Test fun `completed jsonl succeeds`() {
        val result = terminalEvent("{\"type\":\"turn.completed\"}\\n")
        assertThat(result is AgentEvent.TurnCompleted).isEqualTo(true)
    }
}
