package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContainerLifecycleManagerTest {

    private val manager = ContainerLifecycleManager(
        logger = StructuredLogger(emptyList()),
        portRange = 45000..45010
    )

    @Test
    fun `allocatePort returns port in range`() {
        val port = manager.allocatePort()
        assertThat(port in 45000..45010).isTrue()
    }

    @Test
    fun `buildRunCommand injects one -e per env var and appends image last`() {
        val cmd = manager.buildRunCommand(
            containerName = "koncerto-demo-1",
            network = "net",
            hostPort = 32768,
            containerPort = 8080,
            image = "koncerto-demo-fle-52",
            envVars = linkedMapOf("BREVO_API_KEY" to "xkeysib-secret", "DEBUG_TOKEN" to "tok")
        )
        assertThat(cmd).contains("BREVO_API_KEY=xkeysib-secret")
        assertThat(cmd).contains("DEBUG_TOKEN=tok")
        // Image must be the final argument so the -e flags apply to it.
        assertThat(cmd.last()).isEqualTo("koncerto-demo-fle-52")
        // Exactly two -e flags for two env entries.
        assertThat(cmd.count { it == "-e" }).isEqualTo(2)
    }

    @Test
    fun `buildRunCommand with no env vars has no -e flags`() {
        val cmd = manager.buildRunCommand("c", "net", 32768, 8080, "img", emptyMap())
        assertThat(cmd.count { it == "-e" }).isEqualTo(0)
        assertThat(cmd.last()).isEqualTo("img")
    }

    @Test
    fun `buildExecCommand wraps the command in sh -c`() {
        val cmd = manager.buildExecCommand("abc123", "alembic upgrade head")
        assertThat(cmd).isEqualTo(listOf("docker", "exec", "abc123", "sh", "-c", "alembic upgrade head"))
    }

    @Test
    fun `execCommand fails for a missing container`() {
        val result = manager.execCommand("nonexistent-container-id", "echo hi")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `releasePort allows reallocation`() {
        val port = manager.allocatePort()
        manager.releasePort(port)
        val port2 = manager.allocatePort()
        assertThat(port2).isGreaterThan(0)
    }

    @Test
    fun `captureLogs returns message when container missing`() {
        val logs = manager.captureLogs("nonexistent-container-id")
        assertThat(logs.isNotBlank()).isTrue()
    }

    @Test
    fun `stopAndRemove does not throw for missing container`() {
        manager.stopAndRemove("nonexistent-container-id")
    }

    @Test
    fun `runContainer retries on port conflict`() {
        val manager = ContainerLifecycleManager(
            logger = StructuredLogger(emptyList()),
            portRange = 45100..45102
        )
        val port1 = manager.allocatePort()
        val result = manager.runContainer("nonexistent-image:tag", port1, 8080)
        manager.releasePort(port1)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `buildImage fails for invalid dockerfile`() {
        val result = manager.buildImage(
            java.nio.file.Path.of("."),
            java.nio.file.Path.of("/nonexistent/Dockerfile"),
            "koncerto-test-invalid"
        )
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `buildImage succeeds with fake docker`() {
        FakeDockerPath.withFakeDocker(FakeDockerPath.lifecycleSuccessScript()) {
            val tmpDir = Files.createTempDirectory("docker-build-test")
            val dockerfile = tmpDir.resolve("Dockerfile")
            Files.writeString(dockerfile, "FROM alpine\n")
            val result = manager.buildImage(tmpDir, dockerfile, "koncerto-test-build")
            assertThat(result.isSuccess).isTrue()
            tmpDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `allocatePort throws when range exhausted`() {
        val tightManager = ContainerLifecycleManager(
            logger = StructuredLogger(emptyList()),
            portRange = 45300..45300
        )
        val port = tightManager.allocatePort()
        assertThat(port).isEqualTo(45300)
        assertThrows<IllegalStateException> { tightManager.allocatePort() }
        tightManager.releasePort(port)
    }

    @Test
    fun `waitForHealthy returns failure when container never running`() {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  inspect) echo "starting"; exit 0 ;;
esac
exit 0
"""
        FakeDockerPath.withFakeDocker(script) {
            val result = manager.waitForHealthy("test-container", timeoutSec = 1)
            assertThat(result.isFailure).isTrue()
            assertThat(result.exceptionOrNull()!!.message!!.contains("not healthy")).isTrue()
        }
    }

    @Test
    fun `getOccupiedPorts parses docker ps output without throwing`() {
        val method = ContainerLifecycleManager::class.java.getDeclaredMethod("getOccupiedPorts")
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val occupied = method.invoke(manager) as Set<Int>
        assertThat(occupied.all { it in 1..65535 }).isTrue()
    }

    @Test
    fun `runContainer stops retrying on non port conflict error`() {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  run) echo "invalid reference format"; exit 1 ;;
esac
exit 1
"""
        FakeDockerPath.withFakeDocker(script) {
            val port = manager.allocatePort()
            val result = manager.runContainer("bad-image", port, 8080)
            assertThat(result.isFailure).isTrue()
            manager.releasePort(port)
        }
    }

    @Test
    fun `isPortFree returns true for available port`() {
        val method = ContainerLifecycleManager::class.java.getDeclaredMethod("isPortFree", Int::class.javaPrimitiveType)
        method.isAccessible = true
        val freePort = (50000..50100).firstOrNull { port ->
            method.invoke(manager, port) as Boolean
        }
        assertThat(freePort).isNotNull()
    }
}
