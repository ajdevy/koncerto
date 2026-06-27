package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.Workspace
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class DemoScenarioGenerator(
    private val opencodeCommand: String,
    private val logger: StructuredLogger,
    private val processRunner: ProcessRunner = defaultProcessRunner()
) {
    fun interface ProcessRunner {
        fun run(command: List<String>, workDir: File, timeoutSeconds: Long): String?
    }

    fun generate(issue: Issue, workspace: Workspace): String? {
        TODO("implement in Task 3")
    }

    internal fun buildPrompt(issue: Issue, workspace: Workspace): String {
        TODO("implement in Task 2")
    }

    internal fun extractScenarioBlock(raw: String): String? {
        val fenceMatch = Regex(
            """```(?:yaml|yml)\s+demo_scenario\s*\n(.*?)\n```""",
            RegexOption.DOT_MATCHES_ALL
        ).find(raw)
        if (fenceMatch != null) {
            val yamlContent = fenceMatch.groupValues[1].trim()
            if (yamlContent.isBlank()) return null
            return "demo_scenario:\n" + yamlContent.lines().joinToString("\n") { line ->
                if (line.startsWith("  ")) line else "  $line"
            }
        }
        val rawMatch = Regex("""demo_scenario:\s*\n(?:[ \t].*\n?)+""").find(raw)
        if (rawMatch != null) {
            val block = rawMatch.value.trimEnd()
            if (block.lines().drop(1).all { it.isBlank() }) return null
            return block
        }
        return null
    }

    companion object {
        fun defaultProcessRunner(): ProcessRunner = ProcessRunner { command, workDir, timeoutSeconds ->
            try {
                val pb = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
                val process = pb.start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@ProcessRunner null
                }
                if (process.exitValue() != 0) null else output.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
