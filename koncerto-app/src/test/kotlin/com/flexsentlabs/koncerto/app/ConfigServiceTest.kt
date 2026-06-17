package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.core.result.runCatchingResult
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ConfigServiceTest {

    @Test
    fun `loadConfig returns WorkflowDefinition for valid file`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val result = service.loadConfig()
        assertThat(result).isInstanceOf(Result.Success::class)
        val def = (result as Result.Success).value
        assertThat(def.config).isNotEmpty()
        assertThat(def.promptTemplate).isEqualTo("Test prompt")
    }

    @Test
    fun `loadConfig returns failure when file does not exist`() {
        val service = ConfigService("/nonexistent/path/workflow.md")
        val result = service.loadConfig()
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `loadServiceConfig returns ServiceConfig for valid file`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val result = service.loadServiceConfig()
        assertThat(result).isInstanceOf(Result.Success::class)
        val config = (result as Result.Success).value
        assertThat(config.pollIntervalMs).isEqualTo(30000L)
    }

    @Test
    fun `loadServiceConfig returns failure when file does not exist`() {
        val service = ConfigService("/nonexistent/path/workflow.md")
        val result = service.loadServiceConfig()
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `validateConfig succeeds for valid config`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val configMap = mapOf(
            "poll_interval_ms" to 30000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "test-key", "project_slug" to "TEST"),
                    "workspace" to mapOf("root" to "/tmp"),
                    "agent" to mapOf("kind" to "opencode", "max_turns" to 5)
                )
            )
        )
        val result = service.validateConfig(configMap)
        assertThat(result).isInstanceOf(Result.Success::class)
    }

    @Test
    fun `validateConfig fails for config without api_key`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val configMap = mapOf(
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf("kind" to "linear"),
                    "workspace" to mapOf("root" to "/tmp"),
                    "agent" to mapOf("kind" to "opencode", "max_turns" to 5)
                )
            )
        )
        val result = service.validateConfig(configMap)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `saveConfig writes valid config to file`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val configMap = mapOf(
            "poll_interval_ms" to 15000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "key", "project_slug" to "SLUG"),
                    "workspace" to mapOf("root" to "/tmp"),
                    "agent" to mapOf("kind" to "opencode", "max_turns" to 5)
                )
            )
        )
        val result = service.saveConfig(configMap)
        assertThat(result).isInstanceOf(Result.Success::class)
        val content = Files.readString(file)
        assertThat(content).contains("poll_interval_ms")
        assertThat(content).contains("---")
    }

    @Test
    fun `saveConfig returns failure when validation fails`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val configMap = mapOf(
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf("kind" to "linear"),
                    "workspace" to mapOf("root" to "/tmp"),
                    "agent" to mapOf("kind" to "opencode", "max_turns" to 5)
                )
            )
        )
        val result = service.saveConfig(configMap)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `saveConfig creates file when it does not exist`() {
        val file = Files.createTempFile("config-save", ".md").also { Files.deleteIfExists(it) }
        val service = ConfigService(file.toString())
        val configMap = mapOf(
            "poll_interval_ms" to 15000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf("kind" to "linear", "api_key" to "key", "project_slug" to "SLUG"),
                    "workspace" to mapOf("root" to "/tmp"),
                    "agent" to mapOf("kind" to "opencode", "max_turns" to 5)
                )
            )
        )
        val result = service.saveConfig(configMap)
        assertThat(result).isInstanceOf(Result.Success::class)
        assertThat(Files.exists(file)).isTrue()
        Files.deleteIfExists(file)
    }

    @Test
    fun `saveRawYaml fails for invalid YAML`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val result = service.saveRawYaml("not: valid: yaml: [[[")
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `saveRawYaml fails for empty YAML`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val result = service.saveRawYaml("")
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `saveRawYaml fails when root is not a map`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val result = service.saveRawYaml("just a string")
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `saveRawYaml succeeds for valid YAML map with projects`() {
        val file = createWorkflowFile()
        val service = ConfigService(file.toString())
        val yaml = "poll_interval_ms: 15000"
        val result = service.saveRawYaml(yaml)
        if (result is Result.Failure) {
            println("Failure: ${result.error.message}")
            result.error.printStackTrace()
        }
        assertThat(result).isInstanceOf(Result.Success::class)
        val content = Files.readString(file)
        assertThat(content).contains("poll_interval_ms")
    }

    @Test
    fun `getWorkflowPath returns the configured path`() {
        val service = ConfigService("/custom/path/workflow.md")
        assertThat(service.getWorkflowPath()).isEqualTo("/custom/path/workflow.md")
    }

    private fun createWorkflowFile(): Path {
        val file = Files.createTempFile("config-test", ".md")
        Files.writeString(
            file,
            """
            |---
            |poll_interval_ms: 30000
            |projects:
            |  test-project:
            |    tracker:
            |      kind: linear
            |      api_key: test-key
            |      project_slug: TEST
            |    workspace:
            |      root: /tmp/test
            |    agent:
            |      kind: opencode
            |      max_turns: 5
            |---
            |Test prompt
            """.trimMargin()
        )
        return file
    }
}
