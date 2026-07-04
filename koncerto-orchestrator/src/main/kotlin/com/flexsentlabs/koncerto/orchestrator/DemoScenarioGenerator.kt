package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import com.flexsentlabs.koncerto.workspace.Workspace
import java.io.File
import java.util.concurrent.TimeUnit

class DemoScenarioGenerator(
    private val opencodeCommand: String,
    private val logger: StructuredLogger,
    private val processRunner: ProcessRunner = defaultProcessRunner(),
    private val workflowCache: WorkflowCache? = null
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
        val models = FreeModelCycler.DEFAULT_FREE_MODELS
        for (model in models) {
            val cmd = opencodeCommand.split(" ") + listOf("run", "--model", model, prompt)
            // Free-tier models reasoning over a real diff + writing a scenario file via tool
            // calls routinely take 100-150s with real variance run to run; measured one real
            // PR at both 110s and 139s. Leave real headroom rather than re-tuning to the edge.
            val output = processRunner.run(cmd, workDir, 240)
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
        // The demo-scenario system prompt is a koncerto-provided template (like implement.md /
        // review.md), not something individual target projects ship — it must resolve relative
        // to koncerto's own workflow directory, not the target project's own workspace, or it's
        // silently never found and the model gets no formatting instructions at all.
        val systemPromptFile = workflowCache?.workflowDir?.resolve("prompts/demo-scenario.md")?.toFile()
            ?: workspace.path.resolve("prompts/demo-scenario.md").toFile()
        val systemPrompt = if (systemPromptFile.exists()) systemPromptFile.readText() else ""

        val readmeFile = workspace.path.resolve("README.md").toFile()
        val readme = if (readmeFile.exists()) readmeFile.readText().take(3000) else ""

        val diff = runGitDiff(workspace.path.toFile())?.take(8000) ?: ""

        return buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine(systemPrompt)
                appendLine()
            }
            appendLine("## Demo Priorities")
            appendLine("- Demonstrate the PR's created functionality, not generic project documentation or API explorers.")
            appendLine("- If the PR includes a user-facing website, start from the main landing page or index page (`/` or `index.html`) and show the new UI there.")
            appendLine("- Do not default to Swagger/OpenAPI/docs pages unless the PR specifically changes API docs or is only backend/API work with no user-facing website.")
            appendLine("- If the PR touches multiple user-facing endpoints or features, cover the main ones in one coherent flow.")
            appendLine()
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
            append("Generate the demo_scenario YAML now. The scenario must include scrolling and button pressing, with at least one scroll action and at least one button click.")
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
        // Format A: ```yaml demo_scenario\n<content without the demo_scenario: wrapper>\n``` —
        // the label is on the fence line itself, so the content needs wrapping/reindenting.
        val labeledFenceMatch = Regex(
            """```(?:yaml|yml)\s+demo_scenario\s*\n(.*?)\n```""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(raw).lastOrNull()
        if (labeledFenceMatch != null) {
            val yamlContent = labeledFenceMatch.groupValues[1].trim()
            if (yamlContent.isBlank()) return null
            return "demo_scenario:\n" + yamlContent.lines().joinToString("\n") { line ->
                if (line.startsWith("  ") || line.isBlank()) line else "  $line"
            }
        }

        // Format B: plain ```yaml fence (no label) whose content already starts with
        // "demo_scenario:" — the format demo-scenario.md's own example teaches, and what real
        // models actually produce. Prefer the LAST such block: models sometimes read/critique an
        // earlier draft before writing a final revised one.
        val plainFenceMatch = Regex(
            """```(?:yaml|yml)\s*\n(.*?)\n```""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(raw)
            .map { it.groupValues[1] }
            .filter { it.trimStart().startsWith("demo_scenario:") }
            .lastOrNull()
        if (plainFenceMatch != null) {
            return plainFenceMatch.trim().ifBlank { null }
        }

        // Format C: unfenced "demo_scenario:\n  ..." — tolerate blank lines *within* the block
        // (models routinely add them for readability) without swallowing unrelated trailing
        // prose once genuine non-indented, non-blank content appears.
        val rawMatch = Regex("""demo_scenario:\s*\n(?:(?:[ \t]+.*)?\n)*""")
            .findAll(raw)
            .lastOrNull { it.value.trim() != "demo_scenario:" }
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
                // Drain stdout on a separate thread WHILE waiting, not after. A verbose opencode
                // transcript (tool-call previews, sub-agent narration) routinely exceeds the OS
                // pipe buffer; with nothing reading it, the child blocks on write() and waitFor()
                // just spins until the timeout — every real invocation "times out" even though
                // the model itself finished, because nobody ever drained its output.
                val outputBuilder = StringBuilder()
                val readerThread = Thread {
                    try {
                        process.inputStream.bufferedReader().forEachLine { line ->
                            outputBuilder.append(line).append('\n')
                        }
                    } catch (_: Exception) {
                    }
                }
                readerThread.isDaemon = true
                readerThread.start()
                val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    readerThread.join(2000)
                    return@ProcessRunner null
                }
                readerThread.join(5000)
                val output = outputBuilder.toString()
                if (process.exitValue() != 0) null else output.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
