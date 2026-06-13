package com.anomaly.koncerto.e2e

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@Tag("e2e")
@EnabledIfSystemProperty(named = "koncerto.e2e.codex", matches = "true")
class CodexE2eTest {

    @Test
    fun `codex exec creates hello_world py`() {
        val workspaceDir = Files.createTempDirectory("koncerto-e2e-codex-")
        try {
            val output = runCodexExec(workspaceDir)

            val helloFile = workspaceDir.resolve("hello_world.py")
            if (Files.exists(helloFile)) {
                val content = helloFile.toFile().readText()
                assertThat(content).contains("Hello")
            } else {
                println("codex exec output (first 2000 chars): ${output.take(2000)}")
                assertThat(Files.exists(helloFile)).isTrue()
            }
        } finally {
            workspaceDir.toFile().deleteRecursively()
        }
    }

    private fun runCodexExec(cwd: java.nio.file.Path): String {
        val model = System.getenv("CODEX_MODEL") ?: "opencode/deepseek-v4-flash-free"
        val task = "Create a Python script named hello_world.py " +
            "in the workspace root directory. " +
            "The script should print " +
            "'Hello from Koncerto E2E' when executed."

        val proc = ProcessBuilder(
            "npx", "--yes", "@openai/codex", "exec",
            "-m", model,
            "-s", "danger-full-access",
            "-C", cwd.toString(),
            task
        )
            .redirectErrorStream(true)
            .start()
        proc.outputStream.close()

        val output = StringBuilder()
        val reader = BufferedReader(InputStreamReader(proc.inputStream))
        val captureThread = Thread {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }
        captureThread.isDaemon = true
        captureThread.start()

        val exited = proc.waitFor(120, TimeUnit.SECONDS)
        if (!exited) {
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
            val outPreview = output.toString().take(2000)
            throw AssertionError("codex exec timed out after 120s. Output so far:\n$outPreview")
        }
        captureThread.join(1000)
        return output.toString()
    }
}