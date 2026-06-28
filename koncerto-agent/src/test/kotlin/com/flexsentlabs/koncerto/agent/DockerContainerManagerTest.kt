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
