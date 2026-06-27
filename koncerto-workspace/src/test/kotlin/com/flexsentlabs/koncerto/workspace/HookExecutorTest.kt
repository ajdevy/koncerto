package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import assertk.assertions.messageContains
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class HookExecutorTest {

    @TempDir
    lateinit var tmpDir: Path

    private val logger = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `lambda implementation passes workspace path and script`() = runTest {
        var capturedPath: Path? = null
        var capturedScript: String? = null
        val executor = HookExecutor { path, script ->
            capturedPath = path
            capturedScript = script
        }
        executor.run(tmpDir, "echo hello")
        assertThat(capturedPath).isNotNull()
        assertThat(capturedPath).isEqualTo(tmpDir)
        assertThat(capturedScript).isEqualTo("echo hello")
    }

    @Test
    fun `anonymous object implementation works`() = runTest {
        var invoked = false
        val executor = object : HookExecutor {
            override suspend fun run(workspacePath: Path, script: String) {
                invoked = true
            }
        }
        executor.run(tmpDir, "test")
        assertThat(invoked).isTrue()
    }

    @Test
    fun `ShellHookExecutor succeeds with simple script`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "echo success")
    }

    @Test
    fun `ShellHookExecutor throws on non-zero exit`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "exit 1")
        }
        assertThat(ex).messageContains("hook_exit_1")
    }

    @Test
    fun `ShellHookExecutor includes stdout in exception message`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "echo fail_msg && exit 2")
        }
        assertThat(ex).messageContains("hook_exit_2")
        assertThat(ex).messageContains("fail_msg")
    }

    @Test
    fun `ShellHookExecutor includes stderr via redirectErrorStream`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "echo err_output >&2 && exit 3")
        }
        assertThat(ex).messageContains("err_output")
    }

    @Test
    fun `ShellHookExecutor timeout throws hook_timeout`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "sleep 60")
        }
        assertThat(ex).messageContains("hook_timeout")
    }

    @Test
    fun `ShellHookExecutor runs in workspace directory`() = runTest {
        val marker = tmpDir.resolve("created.txt")
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "touch created.txt")
        assertThat(Files.exists(marker)).isTrue()
    }

    @Test
    fun `ShellHookExecutor true script succeeds`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "true")
    }

    @Test
    fun `ShellHookExecutor handles large output without deadlock`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 10_000, logger = logger)
        executor.run(tmpDir, "yes x | head -c 300000")
    }

    @Test
    fun `ShellHookExecutor trims output to 2000 chars in exception`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "python3 -c 'print(\"x\" * 5000)' && exit 5")
        }
        assertThat(ex).messageContains("hook_exit_5")
        val msg = ex.message ?: ""
        assertThat(msg.length).isEqualTo(14 + 2000.coerceAtMost(msg.length - 14))
    }

    @Test
    fun `ShellHookExecutor shell syntax error throws`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        val ex = assertThrows<HookExecutionException> {
            executor.run(tmpDir, "if true then echo bad; fi")
        }
        assertThat(ex).messageContains("hook_exit")
    }

    @Test
    fun `ShellHookExecutor multiple commands in script`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "mkdir sub && touch sub/file.txt")
        assertThat(Files.exists(tmpDir.resolve("sub/file.txt"))).isTrue()
    }

    @Test
    fun `HookExecutionException carries message and cause`() {
        val cause = RuntimeException("root cause")
        val ex = HookExecutionException("test error", cause)
        assertThat(ex.message).isEqualTo("test error")
        assertThat(ex.cause).isNotNull()
        assertThat(ex.cause!!::class.qualifiedName).isEqualTo("java.lang.RuntimeException")
    }

    @Test
    fun `ShellHookExecutor can create file and verify content`() = runTest {
        val executor = ShellHookExecutor(timeoutMs = 5000, logger = logger)
        executor.run(tmpDir, "echo 'file content' > output.txt")
        val content = Files.readString(tmpDir.resolve("output.txt"))
        assertThat(content.trim()).isEqualTo("file content")
    }
}
