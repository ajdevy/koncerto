package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.Workspace
import java.io.File
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
        val prompt = buildPrompt(issue, workspace)
        val output = runWithFallback(prompt, workspace.path.toFile()) ?: return null
        val block = extractScenarioBlock(output) ?: run {
            logger.warn("demo_scenario_extract_failed", mapOf("issue_id" to issue.id))
            return null
        }
        return saveScenario(issue, block)
    }

    private fun runWithFallback(prompt: String, workDir: File): String? {
        val models = listOf("opencode-free-1", "opencode-free-2", "opencode-free-3")
        for (model in models) {
            val cmd = opencodeCommand.split(" ") + listOf("run", "--model", model, prompt)
            val output = processRunner.run(cmd, workDir, 60)
            if (output != null) return output
            logger.warn("demo_scenario_model_failed", mapOf("model" to model))
        }
        logger.warn("demo_scenario_all_models_failed", mapOf("models" to models.joinToString(",")))
        return null
    }

    private fun saveScenario(issue: Issue, block: String): String? {
        val scenarioDir = java.nio.file.Paths.get("/tmp/koncerto-demo")
        return try {
            java.nio.file.Files.createDirectories(scenarioDir)
            val uuidPath = scenarioDir.resolve("${issue.id}-scenario.yaml")
            val identPath = scenarioDir.resolve("${issue.identifier}-scenario.yaml")
            java.nio.file.Files.writeString(uuidPath, block)
            java.nio.file.Files.writeString(identPath, block)
            logger.info("demo_scenario_saved", mapOf(
                "issue_id" to issue.id,
                "issue_identifier" to issue.identifier
            ))
            uuidPath.toString()
        } catch (e: Exception) {
            logger.warn("demo_scenario_save_failed", mapOf(
                "issue_id" to issue.id,
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }

    internal fun buildPrompt(issue: Issue, workspace: Workspace): String {
        val systemPromptFile = workspace.path.resolve("prompts/demo-scenario.md").toFile()
        val systemPrompt = if (systemPromptFile.exists()) systemPromptFile.readText() else ""

        val readmeFile = workspace.path.resolve("README.md").toFile()
        val readme = if (readmeFile.exists()) readmeFile.readText().take(3000) else ""

        val diff = runGitDiff(workspace.path.toFile())?.take(8000) ?: ""

        return buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine(systemPrompt)
                appendLine()
            }
            appendLine("## Issue")
            appendLine("${issue.title}: ${issue.description ?: ""}")
            appendLine()
            if (readme.isNotBlank()) {
                appendLine("## Project README")
                appendLine(readme)
                appendLine()
            }
            if (diff.isNotBlank()) {
                appendLine("## PR Changes")
                appendLine(diff)
                appendLine()
            }
            append("Generate the demo_scenario YAML now.")
        }
    }

    private fun runGitDiff(workDir: File): String? = try {
        val pb = ProcessBuilder("git", "diff", "main...HEAD")
            .directory(workDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(30, TimeUnit.SECONDS)
        output.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logger.warn("demo_scenario_git_diff_failed", mapOf("error" to (e.message ?: "unknown")))
        null
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
            return rawMatch.value.trimEnd()
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
