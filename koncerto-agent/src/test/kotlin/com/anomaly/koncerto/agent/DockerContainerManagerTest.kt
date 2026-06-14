package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.config.DockerConfig
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
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
}
