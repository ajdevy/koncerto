package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ShellHookExecutorTest {

    @TempDir
    lateinit var tmpDir: java.nio.file.Path

    private val logger = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `successful script completes without exception`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "echo hello")
    }

    @Test
    fun `non-zero exit throws HookExecutionException with output`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "echo oops && exit 1")
        }
        assertThat(ex).messageContains("hook_exit_1")
        assertThat(ex).messageContains("oops")
    }

    @Test
    fun `non-zero exit includes truncated output`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "echo abc && exit 42")
        }
        assertThat(ex).messageContains("hook_exit_42")
        assertThat(ex).messageContains("abc")
    }

    @Test
    fun `script runs inside workspace directory`() = runTest {
        val marker = tmpDir.resolve("marker.txt")
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "touch marker.txt")
        assertThat(Files.exists(marker)).isTrue()
    }

    @Test
    fun `empty script succeeds with exit 0`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "true")
    }

    @Test
    fun `timeout throws HookExecutionException`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 10, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "sleep 30")
        }
        assertThat(ex).messageContains("hook_timeout")
    }

    @Test
    fun `script with stderr output is captured via redirectErrorStream`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "echo errormsg >&2 && exit 1")
        }
        assertThat(ex).messageContains("errormsg")
    }
}
