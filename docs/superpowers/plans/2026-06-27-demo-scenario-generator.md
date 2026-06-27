# Demo Scenario Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the review-agent's bolted-on demo scenario generation with a dedicated `DemoScenarioGenerator` service that calls opencode free models with a focused prompt (PR diff + issue description + README) to produce rich, interactive Playwright scenarios.

**Architecture:** A new `DemoScenarioGenerator` class in `koncerto-orchestrator` shells out to `opencode run --model <model> <prompt>` and captures stdout. It is wired into `AutoReviewOrchestrator` as a nullable constructor parameter, called right after review passes. The review prompt is cleaned up to remove the now-redundant demo scenario generation instructions.

**Tech Stack:** Kotlin, JUnit 5, assertk, `java.lang.ProcessBuilder`, `kotlinx.coroutines`

---

## File Map

| Action | Path |
|--------|------|
| **Create** | `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt` |
| **Create** | `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt` |
| **Modify** | `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt` |
| **Modify** | `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestratorTest.kt` |
| **Modify** | `prompts/review.md` |

---

## Task 1: `extractScenarioBlock` — parse YAML from LLM output

The entry point for all scenario parsing. Pure function — no I/O, easy to test first.

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt`
- Create: `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt`

- [ ] **Step 1: Create `DemoScenarioGeneratorTest.kt` with failing tests for `extractScenarioBlock`**

```kotlin
// koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt
package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import org.junit.jupiter.api.Test

class DemoScenarioGeneratorTest {

    private fun logger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun generator() = DemoScenarioGenerator(
        opencodeCommand = "opencode",
        logger = logger()
    )

    @Test
    fun `extractScenarioBlock returns null when no demo_scenario key present`() {
        val raw = "Some review text\nno scenario here"
        assertThat(generator().extractScenarioBlock(raw)).isNull()
    }

    @Test
    fun `extractScenarioBlock parses raw demo_scenario block`() {
        val raw = """
            Review passed.
            demo_scenario:
              description: "Test scenario"
              steps:
                - action: navigate
                  url: /
                - action: wait
                  ms: 1000
        """.trimIndent()
        val result = generator().extractScenarioBlock(raw)
        assertThat(result).isNotNull()
        assertThat(result!!.startsWith("demo_scenario:")).isEqualTo(true)
        assertThat(result.contains("action: navigate")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock parses fenced yaml demo_scenario block`() {
        val raw = """
            Review passed.
            ```yaml demo_scenario
            description: "Fenced scenario"
            steps:
              - action: click
                selector: "text=Submit"
            ```
        """.trimIndent()
        val result = generator().extractScenarioBlock(raw)
        assertThat(result).isNotNull()
        assertThat(result!!.startsWith("demo_scenario:")).isEqualTo(true)
        assertThat(result.contains("action: click")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock returns null for empty demo_scenario section`() {
        val raw = "demo_scenario:\n\nsome other content"
        assertThat(generator().extractScenarioBlock(raw)).isNull()
    }
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest" 2>&1 | tail -20
```
Expected: compilation error — `DemoScenarioGenerator` does not exist yet.

- [ ] **Step 3: Create `DemoScenarioGenerator.kt` with `extractScenarioBlock` only**

```kotlin
// koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt
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
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest.extractScenarioBlock*" 2>&1 | tail -20
```
Expected: all four `extractScenarioBlock` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt \
        koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt
git commit -m "feat: add DemoScenarioGenerator with extractScenarioBlock"
```

---

## Task 2: `buildPrompt` — assemble context from workspace

Assembles the prompt from issue, README, and git diff.

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt`
- Modify: `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt`

- [ ] **Step 1: Add failing tests for `buildPrompt`**

Add these tests to `DemoScenarioGeneratorTest`:

```kotlin
    @Test
    fun `buildPrompt includes issue title and description`(@TempDir tmpDir: java.nio.file.Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1",
            title = "Add checkout button", description = "Users need a checkout button",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Add checkout button")).isEqualTo(true)
        assertThat(prompt.contains("Users need a checkout button")).isEqualTo(true)
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt includes README when present`(@TempDir tmpDir: java.nio.file.Path) {
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), "# My App\nThis app does cool things.")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("My App")).isEqualTo(true)
        assertThat(prompt.contains("This app does cool things.")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt works without README`(@TempDir tmpDir: java.nio.file.Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt includes demo-scenario system prompt when present`(@TempDir tmpDir: java.nio.file.Path) {
        val promptsDir = tmpDir.resolve("prompts").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(promptsDir.resolve("demo-scenario.md"), "You are a demo scenario generator.")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("You are a demo scenario generator.")).isEqualTo(true)
    }
```

Also add `@TempDir` import at the top of the test file:
```kotlin
import org.junit.jupiter.api.io.TempDir
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest.buildPrompt*" 2>&1 | tail -20
```
Expected: FAIL — `buildPrompt` throws `TODO`.

- [ ] **Step 3: Implement `buildPrompt` and `runGitDiff` in `DemoScenarioGenerator`**

Replace the `buildPrompt` `TODO` stub and add `runGitDiff`:

```kotlin
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
```

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest" 2>&1 | tail -20
```
Expected: all tests PASS (both `extractScenarioBlock` and `buildPrompt` groups).

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt \
        koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt
git commit -m "feat: implement DemoScenarioGenerator buildPrompt"
```

---

## Task 3: `generate` — subprocess invocation with model fallback

Implements the full public entry point: calls opencode, falls back across three free models, saves the scenario file.

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt`
- Modify: `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt`

- [ ] **Step 1: Add failing tests for `generate`**

Add these tests to `DemoScenarioGeneratorTest`. Note that `generate` is a suspend function — wrap calls in `runTest { }`.

```kotlin
    import kotlinx.coroutines.test.runTest
    import org.junit.jupiter.api.io.TempDir
    import java.io.File
    import java.nio.file.Path
```

Add to the test class:

```kotlin
    private fun generatorWithRunner(runner: DemoScenarioGenerator.ProcessRunner) =
        DemoScenarioGenerator(opencodeCommand = "opencode", logger = logger(), processRunner = runner)

    private val validScenarioOutput = """
        Here is your demo scenario:
        demo_scenario:
          description: "Checkout flow"
          steps:
            - action: click
              selector: "text=Checkout"
            - action: wait
              ms: 1000
    """.trimIndent()

    @Test
    fun `generate returns scenario path when opencode succeeds on first model`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "Add checkout", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val calledModels = mutableListOf<String>()
        val runner = DemoScenarioGenerator.ProcessRunner { cmd, _, _ ->
            calledModels += cmd[cmd.indexOf("--model") + 1]
            validScenarioOutput
        }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(listOf("opencode-free-1"))
        val savedFile = File("/tmp/koncerto-demo/issue-1-scenario.yaml")
        assertThat(savedFile.exists()).isEqualTo(true)
        assertThat(savedFile.readText().contains("action: click")).isEqualTo(true)
    }

    @Test
    fun `generate falls back to second model when first fails`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-2", identifier = "T-2", title = "Feature", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val calledModels = mutableListOf<String>()
        val runner = DemoScenarioGenerator.ProcessRunner { cmd, _, _ ->
            val model = cmd[cmd.indexOf("--model") + 1]
            calledModels += model
            if (model == "opencode-free-1") null else validScenarioOutput
        }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(listOf("opencode-free-1", "opencode-free-2"))
    }

    @Test
    fun `generate returns null when all models fail`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-3", identifier = "T-3", title = "Feature", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val runner = DemoScenarioGenerator.ProcessRunner { _, _, _ -> null }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNull()
    }

    @Test
    fun `generate returns null when output has no demo_scenario block`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-4", identifier = "T-4", title = "Feature", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val runner = DemoScenarioGenerator.ProcessRunner { _, _, _ -> "Sorry, I cannot help with that." }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNull()
    }
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest.generate*" 2>&1 | tail -20
```
Expected: FAIL — `generate` throws `TODO`.

- [ ] **Step 3: Implement `generate`, `runWithFallback`, and `saveScenario` in `DemoScenarioGenerator`**

Replace the `generate` `TODO` stub and add the two private helpers:

```kotlin
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
        val scenarioDir = Paths.get("/tmp/koncerto-demo")
        return try {
            Files.createDirectories(scenarioDir)
            val uuidPath = scenarioDir.resolve("${issue.id}-scenario.yaml")
            val identPath = scenarioDir.resolve("${issue.identifier}-scenario.yaml")
            Files.writeString(uuidPath, block)
            Files.writeString(identPath, block)
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
```

- [ ] **Step 4: Run all `DemoScenarioGeneratorTest` tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.DemoScenarioGeneratorTest" 2>&1 | tail -30
```
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGenerator.kt \
        koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DemoScenarioGeneratorTest.kt
git commit -m "feat: implement DemoScenarioGenerator.generate with model fallback"
```

---

## Task 4: Wire `DemoScenarioGenerator` into `AutoReviewOrchestrator`

Remove the old `saveDemoScenario` / `extractScenarioBlock` private methods and replace the call site.

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt`

- [ ] **Step 1: Add `demoScenarioGenerator` constructor parameter**

In `AutoReviewOrchestrator.kt`, add as the last constructor parameter (nullable, defaults to `null`):

```kotlin
    private val demoScenarioGenerator: DemoScenarioGenerator? = null
```

The full constructor becomes:
```kotlin
class AutoReviewOrchestrator(
    private val agentRunner: AgentRunner,
    private val workspaceManager: WorkspaceManager,
    private val linearClient: TrackerClient,
    private val projectConfig: ProjectConfig,
    private val projectSlug: String,
    private val runtimeState: RuntimeState,
    private val notifier: CompositeNotifier?,
    private val logger: StructuredLogger,
    private val workflowCache: WorkflowCache? = null,
    private val onReviewPassed: (suspend (Issue, targetUrl: String?) -> String?)? = null,
    private val targetProjectDeployer: TargetProjectDeployer? = null,
    private val deployRepoFullName: String? = null,
    private val demoFailureReporter: DemoFailureReporter? = null,
    private val demoScenarioGenerator: DemoScenarioGenerator? = null
)
```

- [ ] **Step 2: Replace the `saveDemoScenario` call in `onCodingComplete`**

Find this block (~line 92 in `AutoReviewOrchestrator.kt`):
```kotlin
            saveDemoScenario(issue, workspace)
            val deployResult = deployTargetProject(issue, workspace)
```

Replace with:
```kotlin
            demoScenarioGenerator?.generate(issue, workspace)
            val deployResult = deployTargetProject(issue, workspace)
```

- [ ] **Step 3: Delete `saveDemoScenario` and `extractScenarioBlock` private methods**

Delete the entire `saveDemoScenario` method (lines ~223–271) and the entire `extractScenarioBlock` method (lines ~206–221) from `AutoReviewOrchestrator.kt`. These are now in `DemoScenarioGenerator`.

- [ ] **Step 4: Run existing orchestrator tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.AutoReviewOrchestratorTest" 2>&1 | tail -30
```
Expected: all pre-existing tests PASS (the `demoScenarioGenerator` param defaults to `null` so nothing breaks).

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt
git commit -m "refactor: wire DemoScenarioGenerator into AutoReviewOrchestrator"
```

---

## Task 5: Add `AutoReviewOrchestratorTest` assertions for scenario generator

Verify that `generate()` is called on pass and not called on fail.

**Files:**
- Modify: `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestratorTest.kt`

- [ ] **Step 1: Add a tracking `DemoScenarioGenerator` helper and two new tests**

Add this helper to the test class (after the existing helpers):

```kotlin
    private val validScenarioYaml = """
        demo_scenario:
          description: "Test"
          steps:
            - action: wait
              ms: 1000
    """.trimIndent()

    private fun trackingScenarioGenerator(): Pair<DemoScenarioGenerator, () -> Int> {
        var callCount = 0
        val gen = DemoScenarioGenerator(
            opencodeCommand = "opencode",
            logger = noopLogger(),
            processRunner = { _, _, _ ->
                callCount++
                validScenarioYaml  // return valid YAML so only first model is tried
            }
        )
        return gen to { callCount }
    }
```

Add these two tests:

```kotlin
    @Test
    fun `demoScenarioGenerator generate is called when review passes`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "pass")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 3, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val (gen, callCount) = trackingScenarioGenerator()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = gen
        )
        orchestrator.onCodingComplete(issue())
        assertThat(callCount()).isEqualTo(1)
    }

    @Test
    fun `demoScenarioGenerator generate is NOT called when review fails`(@TempDir tmpDir: Path) = runTest {
        val workspaceDir = tmpDir.resolve("workspace").also { Files.createDirectories(it) }
        val issueDir = workspaceDir.resolve("T-1").also { Files.createDirectories(it) }
        Files.writeString(issueDir.resolve(".review-status"), "fail")

        val reviewStage = StageAgentConfig(
            prompt = null, model = null, effort = null, maxConcurrent = null,
            agentKind = "claude", command = "claude",
            onCompleteState = "Done", onFailureState = "In Progress",
            maxReviewAttempts = 1, agent = null, followUp = null, crossProjectFollowUp = null
        )
        val (gen, callCount) = trackingScenarioGenerator()
        val orchestrator = AutoReviewOrchestrator(
            agentRunner = fakeRunner(),
            workspaceManager = WorkspaceManager(workspaceDir, HookExecutor { _, _ -> }),
            linearClient = fakeTracker(),
            projectConfig = projectConfig(stages = mapOf("in review" to reviewStage), workspaceRoot = workspaceDir.toString()),
            projectSlug = "p",
            runtimeState = RuntimeState(),
            notifier = noopNotifier(),
            logger = noopLogger(),
            demoScenarioGenerator = gen
        )
        orchestrator.onCodingComplete(issue())
        assertThat(callCount()).isEqualTo(0)
    }
```

- [ ] **Step 2: Run all orchestrator tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*.AutoReviewOrchestratorTest" 2>&1 | tail -30
```
Expected: all tests PASS including the two new ones.

- [ ] **Step 3: Commit**

```bash
git add koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestratorTest.kt
git commit -m "test: verify DemoScenarioGenerator called on review pass/fail"
```

---

## Task 6: Clean `prompts/review.md`

Remove the demo scenario generation instructions from the review prompt so the review agent focuses on code review only.

**Files:**
- Modify: `prompts/review.md`

- [ ] **Step 1: Remove the "Demo Scenario" section**

Open `prompts/review.md`. Delete the entire block from line 70 to the end of the file (or end of the demo scenario section). The section to remove starts with:

```
## Demo Scenario (if review passed)
```

And ends after the closing rules block (roughly line 90). Remove everything from `## Demo Scenario (if review passed)` to the end of the file (or to the next `---` separator if one follows).

The file should end cleanly at:
```
End with a summary verdict: "✅ Review PASSED" or "❌ Review FAILED — found blocking issue(s)".
```

- [ ] **Step 2: Run the full orchestrator module test suite**

```bash
./gradlew :koncerto-orchestrator:test 2>&1 | tail -20
```
Expected: all tests PASS.

- [ ] **Step 3: Commit**

```bash
git add prompts/review.md
git commit -m "chore: remove demo scenario generation from review prompt"
```

---

## Final Verification

- [ ] **Run the full test suite for all affected modules**

```bash
./gradlew :koncerto-orchestrator:test :koncerto-demo:test 2>&1 | tail -30
```
Expected: all tests PASS, no regressions in `koncerto-demo`.
