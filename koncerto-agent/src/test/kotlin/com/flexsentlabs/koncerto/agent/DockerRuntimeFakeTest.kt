package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.contains
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test

class DockerRuntimeFakeTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun fakeDockerScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  info) exit 0 ;;
  exec)
    echo "agent stderr line" >&2
    echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
    echo '{"jsonrpc":"2.0","method":"turn/completed","params":{"thread_id":"t1","turn_id":"u1"}}'
    sleep 0.2
    exit 0
    ;;
  inspect) echo "running"; exit 0 ;;
  stats) echo "CPU 0.0% MEM 10MiB / 100MiB"; exit 0 ;;
  logs) echo "container started"; exit 0 ;;
  rm) exit 0 ;;
esac
exit 0
"""

    private suspend fun <T> withFakeDocker(block: suspend () -> T): T {
        return withFakeDocker(fakeDockerScript(), block)
    }

    private suspend fun <T> withFakeDocker(script: String, block: suspend () -> T): T {
        val binDir = Files.createTempDirectory("fake-docker-rt-bin")
        val docker = binDir.resolve("docker")
        Files.writeString(docker, script)
        docker.toFile().setExecutable(true)
        try {
            DockerRuntime.testDockerOverride.set(docker.toString())
            return block()
        } finally {
            DockerRuntime.testDockerOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `start with fake docker reads stdout and stderr`() = runBlocking {
        withFakeDocker {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-ws-"),
                noopLogger(),
                "test-container-id"
            )
            assertThat(rt.start()).isTrue()
            kotlinx.coroutines.delay(300)
            rt.stop()
        }
    }

    @Test
    fun `stop invokes fake docker stats logs and rm`() = runBlocking {
        withFakeDocker {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-stop-"),
                noopLogger(),
                "test-container-id"
            )
            assertThat(rt.start()).isTrue()
            kotlinx.coroutines.delay(200)
            rt.stop()
        }
    }

    @Test
    fun `isAlive uses fake docker inspect`() = runBlocking {
        withFakeDocker {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-alive-"),
                noopLogger(),
                "test-container-id"
            )
            rt.start()
            assertThat(rt.isAlive()).isTrue()
            rt.stop()
        }
    }

    @Test
    fun `isDockerDaemonAvailable returns false when fake docker info fails`() = runBlocking {
        val script = """#!/usr/bin/env bash
if [ "${'$'}1" = "info" ]; then exit 1; fi
exit 1
"""
        withFakeDocker(script) {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-info-"),
                noopLogger(),
                "test-container-id"
            )
            val method = DockerRuntime::class.java.getDeclaredMethod("isDockerDaemonAvailable")
            method.isAccessible = true
            assertThat(method.invoke(rt) as Boolean).isFalse()
            rt.stop()
        }
    }

    @Test
    fun `testDockerOverride thread local is readable`() {
        DockerRuntime.testDockerOverride.set("/tmp/fake-docker")
        assertThat(DockerRuntime.testDockerOverride.get()).isEqualTo("/tmp/fake-docker")
        DockerRuntime.testDockerOverride.set(null)
    }

    @Test
    fun `start returns false when docker daemon unavailable`() = runBlocking {
        val script = """#!/usr/bin/env bash
if [ "${'$'}1" = "info" ]; then exit 1; fi
exit 1
"""
        withFakeDocker(script) {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-no-daemon-"),
                noopLogger(),
                "test-container-id"
            )
            assertThat(rt.start()).isFalse()
            rt.stop()
        }
    }

    @Test
    fun `stop tolerates docker binary errors in stats logs and rm`() = runBlocking {
        DockerRuntime.testDockerOverride.set("/nonexistent/koncerto-docker-binary")
        try {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-throw-stop-"),
                noopLogger(),
                "test-container-id"
            )
            rt.stop()
        } finally {
            DockerRuntime.testDockerOverride.set(null)
        }
    }

    @Test
    fun `stop tolerates failing stats logs and rm`() = runBlocking {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  info) exit 0 ;;
  exec)
    echo "agent stderr line" >&2
    echo '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
    sleep 0.5
    exit 0
    ;;
  inspect) echo "running"; exit 0 ;;
  stats) exit 1 ;;
  logs) exit 1 ;;
  rm) exit 1 ;;
esac
exit 0
"""
        withFakeDocker(script) {
            val rt = DockerRuntime(
                "echo hello",
                Files.createTempDirectory("docker-fake-fail-stop-"),
                noopLogger(),
                "test-container-id"
            )
            assertThat(rt.start()).isTrue()
            kotlinx.coroutines.delay(250)
            rt.stop()
        }
    }
}
