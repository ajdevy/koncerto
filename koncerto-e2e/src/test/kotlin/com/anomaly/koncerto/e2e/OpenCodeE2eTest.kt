package com.anomaly.koncerto.e2e

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

@Tag("e2e")
@EnabledIfSystemProperty(named = "koncerto.e2e.opencode", matches = "true")
class OpenCodeE2eTest {

    @Test
    fun `opencode agent creates hello_world py via serve and run`() {
        val opencodeCmd = System.getenv("OPENCODE_COMMAND") ?: "opencode"
        assertThat(isOpencodeInstalled(opencodeCmd)).isTrue()

        val workspaceDir = Files.createTempDirectory("koncerto-e2e-")
        try {
            val port = findAvailablePort()

            val serverProcess = startServer(opencodeCmd, port, workspaceDir)
            try {
                waitForServerUp(serverProcess, port)

                val runOutput = runTask(opencodeCmd, port)
                val output = runOutput.output

                val helloFile = workspaceDir.resolve("hello_world.py")
                if (Files.exists(helloFile)) {
                    val content = helloFile.toFile().readText()
                    assertThat(content).contains("Hello")
                } else {
                    val runProcess = runOutput.process
                    val exitCode = runProcess.exitValue()
                    if (exitCode != 0) {
                        println("opencode run exited with code $exitCode, output: ${output.take(2000)}")
                    }
                    assertThat(Files.exists(helloFile)).isTrue()
                }
            } finally {
                serverProcess.destroyForcibly()
                serverProcess.waitFor(3, TimeUnit.SECONDS)
            }
        } finally {
            workspaceDir.toFile().deleteRecursively()
        }
    }

    private fun isOpencodeInstalled(cmd: String): Boolean {
        return try {
            val proc = ProcessBuilder("bash", "-lc", "command -v $cmd")
                .redirectErrorStream(true)
                .start()
            proc.waitFor(3, TimeUnit.SECONDS)
            proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun findAvailablePort(): Int {
        val socket = ServerSocket(0)
        val port = socket.localPort
        socket.close()
        return port
    }

    private fun startServer(cmd: String, port: Int, cwd: java.nio.file.Path): Process {
        return ProcessBuilder(cmd, "serve", "--port", port.toString())
            .directory(cwd.toFile())
            .redirectErrorStream(true)
            .start()
    }

    private fun waitForServerUp(process: Process, port: Int) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                throw AssertionError("Server process exited prematurely")
            }
            try {
                val s = ServerSocket()
                s.bind(java.net.InetSocketAddress("127.0.0.1", port))
                s.close()
                Thread.sleep(500)
            } catch (_: java.net.BindException) {
                return
            } catch (_: Exception) {
                Thread.sleep(500)
            }
        }
        process.destroyForcibly()
        throw AssertionError("Server did not start on port $port within 30s")
    }

    private data class RunResult(val process: Process, val output: String)

    private fun runTask(cmd: String, port: Int): RunResult {
        val task = "Create a Python script named hello_world.py " +
            "in the workspace root directory. " +
            "The script should print " +
            "'Hello from Koncerto E2E' when executed."
        val proc = ProcessBuilder(
            cmd, "run",
            "--attach", "http://localhost:$port",
            "--dangerously-skip-permissions",
            task
        )
            .redirectErrorStream(true)
            .start()

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
            throw AssertionError("opencode run timed out after 120s")
        }
        captureThread.join(1000)
        return RunResult(proc, output.toString())
    }
}
