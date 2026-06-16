package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import org.junit.jupiter.api.Test

class BeansTest {

    private val beans = Beans()

    @Test
    fun `logger creates instance without logsRoot`() {
        val logger = beans.logger(null)
        assertThat(logger).isNotNull()
    }

    @Test
    fun `logger creates instance with logsRoot`() {
        val tempDir = Files.createTempDirectory("koncerto-beans-test")
        try {
            val logger = beans.logger(tempDir.toString())
            assertThat(logger).isNotNull()
            assertThat(Files.exists(tempDir)).isTrue()
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `workflowCache returns a new instance`() {
        val cache = beans.workflowCache()
        assertThat(cache).isNotNull()
    }

    @Test
    fun `serviceConfig loads from a real workflow file`() {
        val workflowFile = Files.createTempFile("workflow", ".md")
        try {
            Files.writeString(
                workflowFile,
                """
                |---
                |poll_interval_ms: 15000
                |projects:
                |  test-project:
                |    tracker:
                |      kind: linear
                |      api_key: test-key-123
                |      project_slug: TEST
                |    workspace:
                |      root: /tmp/koncerto-test
                |    agent:
                |      kind: opencode
                |---
                |Test prompt body
                """.trimMargin()
            )

            val cache = WorkflowCache()
            val logger = StructuredLogger(emptyList<LogSink>())
            val config = beans.serviceConfig(workflowFile.toString(), cache, logger)

            assertThat(config).isNotNull()
            assertThat(config.pollIntervalMs).isEqualTo(15000L)
            assertThat(config.projects.size).isEqualTo(1)
        } finally {
            Files.deleteIfExists(workflowFile)
        }
    }
}
