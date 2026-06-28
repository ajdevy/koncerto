package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import org.junit.jupiter.api.Test

class DockerContainerManagerTest {

    private val logger = StructuredLogger(emptyList())

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `generateContainerId produces valid container name`() {
        val id = DockerContainerManager.generateContainerId()
        assertThat(id.startsWith("koncerto-agent-")).isTrue()
        assertThat(id.length > "koncerto-agent-".length).isTrue()
    }

    @Test
    fun `generateContainerId contains only valid characters`() {
        val id = DockerContainerManager.generateContainerId()
        val validPattern = Pattern.compile("^[a-zA-Z0-9_.-]+$")
        assertThat(validPattern.matcher(id).matches()).isEqualTo(true)
    }

    @Test
    fun `generateContainerId is unique across calls`() {
        val ids = (1..100).map { DockerContainerManager.generateContainerId() }
        assertThat(ids.toSet().size).isEqualTo(100)
    }

    @Test
    fun `generateContainerId is unique under concurrent calls`() {
        val threads = 20
        val callsPerThread = 50
        val ids = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val latch = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) {
            Thread {
                try {
                    repeat(callsPerThread) { ids.add(DockerContainerManager.generateContainerId()) }
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        latch.await()
        assertThat(ids.size).isEqualTo(threads * callsPerThread)
    }

    @Test
    fun `DockerConfig default values are correct`() {
        val config = DockerConfig()
        assertThat(config.enabled).isEqualTo(true)
        assertThat(config.image).isEqualTo("koncerto-agent:latest")
        assertThat(config.cpu).isEqualTo("auto")
        assertThat(config.memory).isEqualTo("auto")
        assertThat(config.network).isEqualTo(true)
        assertThat(config.dockerfile).isEqualTo("Dockerfile.agent")
    }

    @Test
    fun `DockerConfig custom values are preserved`() {
        val config = DockerConfig(
            enabled = false,
            image = "custom:v1",
            cpu = "4.0",
            memory = "8g",
            network = false,
            dockerfile = "MyDockerfile"
        )
        assertThat(config.enabled).isEqualTo(false)
        assertThat(config.image).isEqualTo("custom:v1")
        assertThat(config.cpu).isEqualTo("4.0")
        assertThat(config.memory).isEqualTo("8g")
        assertThat(config.network).isEqualTo(false)
        assertThat(config.dockerfile).isEqualTo("MyDockerfile")
    }

    @Test
    fun `createContainer returns null when docker is not installed`() {
        val root = Files.createTempDirectory("docker-test-")
        val config = DockerConfig(enabled = true, image = "koncerto-agent:latest")
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        val result = manager.createContainer()
        assertThat(result).isNull()
    }

    @Test
    fun `isContainerRunning returns false when docker is not installed`() {
        val root = Files.createTempDirectory("docker-test-")
        val config = DockerConfig()
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        val result = manager.isContainerRunning("nonexistent-container-id")
        assertThat(result).isFalse()
    }

    @Test
    fun `captureLogs returns null when docker is not installed`() {
        val root = Files.createTempDirectory("docker-test-")
        val config = DockerConfig()
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        val result = manager.captureLogs("nonexistent-container-id")
        assertThat(result).isNull()
    }

    @Test
    fun `removeContainer does not throw when docker is not installed`() {
        val root = Files.createTempDirectory("docker-test-")
        val config = DockerConfig()
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        assertThat(runCatching { manager.removeContainer("nonexistent-container-id") }.isSuccess).isTrue()
    }

    @Test
    fun `pruneOldContainers handles blank or no candidates`() {
        DockerContainerManager.pruneOldContainers(noopLogger(), olderThanHours = 24)
    }

    @Test
    fun `pruneOldContainers via instance delegates to companion`() {
        val root = Files.createTempDirectory("docker-test-")
        val config = DockerConfig()
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        assertThat(runCatching { manager.pruneOldContainers(olderThanHours = 1) }.isSuccess).isTrue()
    }

    @Test
    fun `resolveCpus auto returns capped value`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(cpu = "auto"), root, 4, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("resolveCpus")
        method.isAccessible = true
        val result = method.invoke(manager) as Double
        assertThat(result >= 0.5).isTrue()
    }

    @Test
    fun `resolveCpus explicit value is preserved`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(cpu = "2.5"), root, 4, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("resolveCpus")
        method.isAccessible = true
        val result = method.invoke(manager) as Double
        assertThat(result).isEqualTo(2.5)
    }

    @Test
    fun `resolveMemory auto returns value`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(memory = "auto"), root, 4, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("resolveMemory")
        method.isAccessible = true
        val result = method.invoke(manager) as String
        assertThat(result.endsWith("m")).isTrue()
    }

    @Test
    fun `resolveMemory explicit value is preserved`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(memory = "512m"), root, 4, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("resolveMemory")
        method.isAccessible = true
        val result = method.invoke(manager) as String
        assertThat(result).isEqualTo("512m")
    }

    private fun fakeBashScript() = """#!/usr/bin/env bash
CMD="${'$'}1"
if echo "${'$'}CMD" | grep -q "docker run"; then
  echo "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  exit 0
fi
if echo "${'$'}CMD" | grep -q "docker inspect"; then
  echo running
  exit 0
fi
if echo "${'$'}CMD" | grep -q "docker logs"; then
  echo "container log line"
  exit 0
fi
if echo "${'$'}CMD" | grep -q "docker rm"; then
  exit 0
fi
if echo "${'$'}CMD" | grep -q "docker ps"; then
  echo "old-id|2020-01-01 00:00:00 +0000 UTC"
  exit 0
fi
if echo "${'$'}CMD" | grep -q "free -b"; then
  echo 8000000000
  exit 0
fi
exit 1
"""

    private fun <T> withFakeBash(block: () -> T): T {
        val binDir = Files.createTempDirectory("fake-bash-bin")
        val bash = binDir.resolve("fake-bash")
        Files.writeString(bash, fakeBashScript())
        bash.toFile().setExecutable(true)
        try {
            DockerContainerManager.testBashOverride.set(bash.toString())
            return block()
        } finally {
            DockerContainerManager.testBashOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createContainer succeeds with fake bash docker`() {
        val root = Files.createTempDirectory("docker-fake-")
        val config = DockerConfig(enabled = true, image = "koncerto-agent:latest", network = true)
        val manager = DockerContainerManager(config, root, 2, noopLogger())
        withFakeBash {
            val id = manager.createContainer()
            assertThat(id).isNotNull()
            assertThat(id!!.length).isEqualTo(64)
        }
    }

    @Test
    fun `isContainerRunning returns true with fake bash`() {
        val root = Files.createTempDirectory("docker-fake-")
        val manager = DockerContainerManager(DockerConfig(), root, 2, noopLogger())
        withFakeBash {
            assertThat(manager.isContainerRunning("cid")).isTrue()
        }
    }

    @Test
    fun `captureLogs returns text with fake bash`() {
        val root = Files.createTempDirectory("docker-fake-")
        val manager = DockerContainerManager(DockerConfig(), root, 2, noopLogger())
        withFakeBash {
            assertThat(manager.captureLogs("cid")?.trim()).isEqualTo("container log line")
        }
    }

    @Test
    fun `removeContainer succeeds with fake bash`() {
        val root = Files.createTempDirectory("docker-fake-")
        val manager = DockerContainerManager(DockerConfig(), root, 2, noopLogger())
        withFakeBash {
            manager.removeContainer("cid")
        }
    }

    @Test
    fun `pruneOldContainers removes stale containers with fake bash`() {
        withFakeBash {
            DockerContainerManager.pruneOldContainers(noopLogger(), olderThanHours = 24 * 365)
        }
    }

    @Test
    fun `osFreeMem returns positive value`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(), root, 2, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("osFreeMem")
        method.isAccessible = true
        val result = method.invoke(manager) as Long
        assertThat(result > 0).isTrue()
    }

    @Test
    fun `resolveCpus invalid value falls back to default`() {
        val root = Files.createTempDirectory("docker-test-")
        val manager = DockerContainerManager(DockerConfig(cpu = "not-a-number"), root, 4, noopLogger())
        val method = DockerContainerManager::class.java.getDeclaredMethod("resolveCpus")
        method.isAccessible = true
        val result = method.invoke(manager) as Double
        assertThat(result).isEqualTo(2.0)
    }

    @Test
    fun `pruneOldContainers removes containers older than cutoff`() {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    if [[ "${'$'}*" == *"koncerto-agent-"* ]]; then
      echo "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef|2020-06-01 12:00:00 +0000"
    fi
    exit 0
    ;;
  rm) exit 0 ;;
esac
exit 0
"""
        withFakeDockerInPath(script) {
            DockerContainerManager.pruneOldContainers(noopLogger(), olderThanHours = 1)
        }
    }

    @Test
    fun `pruneOldContainers via testBashOverride removes stale containers`() {
        val script = """#!/usr/bin/env bash
if [[ "${'$'}1" == *"docker ps"* ]]; then
  echo "stale123456789012345678901234567890123456789012345678901234567890|2020-01-01 12:00:00 +0000"
elif [[ "${'$'}1" == *"docker rm"* ]]; then exit 0; fi
exit 0
"""
        val binDir = Files.createTempDirectory("fake-bash-prune-")
        val bash = binDir.resolve("bash")
        Files.writeString(bash, script)
        bash.toFile().setExecutable(true)
        try {
            DockerContainerManager.testBashOverride.set(bash.toString())
            DockerContainerManager.pruneOldContainers(noopLogger(), olderThanHours = 1)
        } finally {
            DockerContainerManager.testBashOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `createContainer returns id with fake bash`() {
        val script = """#!/usr/bin/env bash
if [[ "${'$'}1" == *"docker run"* ]]; then echo "abc123containerid"; exit 0; fi
exit 0
"""
        val binDir = Files.createTempDirectory("fake-bash-create-")
        val bash = binDir.resolve("bash")
        Files.writeString(bash, script)
        bash.toFile().setExecutable(true)
        try {
            DockerContainerManager.testBashOverride.set(bash.toString())
            val manager = DockerContainerManager(
                DockerConfig(image = "koncerto-agent:test"),
                Files.createTempDirectory("docker-create-ws-"),
                2,
                noopLogger()
            )
            val id = manager.createContainer()
            assertThat(id).isEqualTo("abc123containerid")
        } finally {
            DockerContainerManager.testBashOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    private fun withFakeDockerInPath(script: String, block: () -> Unit) {
        val binDir = Files.createTempDirectory("fake-docker-bin")
        val docker = binDir.resolve("docker")
        Files.writeString(docker, script)
        docker.toFile().setExecutable(true)
        val originalPath = System.getenv("PATH") ?: ""
        try {
            prependPath("$binDir:$originalPath")
            block()
        } finally {
            prependPath(originalPath)
            binDir.toFile().deleteRecursively()
        }
    }

    private fun prependPath(path: String) {
        val pe = Class.forName("java.lang.ProcessEnvironment")
        val env = pe.getDeclaredField("theEnvironment")
        env.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (env.get(null) as MutableMap<String, String>)["PATH"] = path
        try {
            val ciEnv = pe.getDeclaredField("theCaseInsensitiveEnvironment")
            ciEnv.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (ciEnv.get(null) as MutableMap<String, String>)["PATH"] = path
        } catch (_: NoSuchFieldException) {
        }
    }
}
