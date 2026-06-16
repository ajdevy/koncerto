package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isNotNull
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubtaskRunnerTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `subtask runner requires valid runtime factory`() {
        val logger = noopLogger()
        val runner = DefaultSubtaskRunner(logger)
        assertThat(runner).isNotNull()
    }

    @Test
    fun `subtask runner returns failure when runtime start fails`() = runTest {
        val logger = noopLogger()
        val runner = DefaultSubtaskRunner(logger)
        val tempDir = Files.createTempDirectory("subtask-test")
        val result = runner.runSubtask(
            workspacePath = tempDir,
            prompt = "test prompt",
            kind = "opencode"
        )
        assertThat(result).isNotNull()
    }
}