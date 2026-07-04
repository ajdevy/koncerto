package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

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

    @Test
    fun `extractScenarioBlock fenced block wraps top-level keys under demo_scenario`() {
        val raw = """
            ```yaml demo_scenario
            description: "Checkout flow"
            steps:
              - action: click
                selector: "text=Submit"
            ```
        """.trimIndent()
        val result = generator().extractScenarioBlock(raw)!!
        val lines = result.lines()
        // First line is the wrapper key
        assertThat(lines[0]).isEqualTo("demo_scenario:")
        // Top-level keys (description, steps) must be indented under demo_scenario:
        val descLine = lines.first { it.trimStart().startsWith("description:") }
        assertThat(descLine.startsWith("  ")).isEqualTo(true)
        val stepsLine = lines.first { it.trimStart().startsWith("steps:") }
        assertThat(stepsLine.startsWith("  ")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock parses unlabeled fence whose content already starts with demo_scenario`() {
        // The actual format real models produce, matching demo-scenario.md's own example: the
        // fence has no inline label, and "demo_scenario:" is the first line of content.
        val raw = """
            Here's the scenario:

            ```yaml
            demo_scenario:
              description: "Login flow"
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
    fun `extractScenarioBlock tolerates blank lines within an unfenced demo_scenario block`() {
        val raw = """
            demo_scenario:
              description: "Login flow"

              steps:
                - action: navigate
                  url: /

                - action: click
                  selector: "text=Submit"
        """.trimIndent()
        val result = generator().extractScenarioBlock(raw)
        assertThat(result).isNotNull()
        assertThat(result!!.contains("action: navigate")).isEqualTo(true)
        assertThat(result.contains("action: click")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock uses the last fenced block when the model revises an earlier draft`() {
        val raw = """
            Reading existing scenario...

            ```yaml
            demo_scenario:
              description: "Draft"
              steps:
                - action: click
                  selector: "text=Old"
            ```

            Actually, let me improve it:

            ```yaml
            demo_scenario:
              description: "Final"
              steps:
                - action: click
                  selector: "text=New"
            ```
        """.trimIndent()
        val result = generator().extractScenarioBlock(raw)
        assertThat(result).isNotNull()
        assertThat(result!!.contains("text=New")).isEqualTo(true)
        assertThat(result.contains("text=Old")).isEqualTo(false)
    }

    @Test
    fun `buildPrompt includes issue title and description`(@TempDir tmpDir: Path) {
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
        assertThat(prompt.contains("main landing page or index page")).isEqualTo(true)
        assertThat(prompt.contains("Do not default to Swagger/OpenAPI/docs pages")).isEqualTo(true)
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
        assertThat(prompt.contains("scrolling")).isEqualTo(true)
        assertThat(prompt.contains("button pressing")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt includes README when present`(@TempDir tmpDir: Path) {
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
    fun `buildPrompt works without README`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
        assertThat(prompt.contains("main landing page or index page")).isEqualTo(true)
        assertThat(prompt.contains("scrolling")).isEqualTo(true)
        assertThat(prompt.contains("button pressing")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt includes demo-scenario system prompt when present`(@TempDir tmpDir: Path) {
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

    @Test
    fun `buildPrompt reads demo-scenario system prompt from koncerto's own workflow dir, not the target project workspace`(
        @TempDir tmpDir: Path,
        @TempDir workflowDir: Path
    ) {
        // The demo-scenario prompt is a koncerto-provided template — target projects don't ship
        // their own copy — so it must resolve relative to koncerto's own workflow directory.
        val promptsDir = workflowDir.resolve("prompts").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(promptsDir.resolve("demo-scenario.md"), "You are a demo scenario generator.")
        val cache = com.flexsentlabs.koncerto.workflow.WorkflowCache()
        cache.setWorkflowDir(workflowDir)

        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val gen = DemoScenarioGenerator(opencodeCommand = "opencode", logger = logger(), workflowCache = cache)
        val prompt = gen.buildPrompt(issue, workspace)
        assertThat(prompt.contains("You are a demo scenario generator.")).isEqualTo(true)
    }

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
    fun `generate returns scenario path when opencode succeeds on first model`(@TempDir tmpDir: java.nio.file.Path) = runTest {
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
        assertThat(calledModels).isEqualTo(listOf(FreeModelCycler.DEFAULT_FREE_MODELS[0]))
        val savedFile = File("/tmp/koncerto-demo/issue-1-scenario.yaml")
        assertThat(savedFile.exists()).isEqualTo(true)
        assertThat(savedFile.readText().contains("action: click")).isEqualTo(true)
    }

    @Test
    fun `generate falls back to second model when first fails`(@TempDir tmpDir: java.nio.file.Path) = runTest {
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
            if (model == FreeModelCycler.DEFAULT_FREE_MODELS[0]) null else validScenarioOutput
        }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(FreeModelCycler.DEFAULT_FREE_MODELS.take(2))
    }

    @Test
    fun `generate returns null when all models fail`(@TempDir tmpDir: java.nio.file.Path) = runTest {
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
    fun `buildPrompt includes git diff when repo has changes`(@TempDir tmpDir: Path) {
        initGitRepoWithDiff(tmpDir, "feature change")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Fix bug", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("## PR Changes")).isEqualTo(true)
        assertThat(prompt.contains("feature change")).isEqualTo(true)
    }

    @Test
    fun `generate writes scenario files by id and identifier`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-save", identifier = "SAVE-1", title = "Save test", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val runner = DemoScenarioGenerator.ProcessRunner { _, _, _ -> validScenarioOutput }
        val result = generatorWithRunner(runner).generate(issue, workspace)

        assertThat(result).isEqualTo("/tmp/koncerto-demo/issue-save-scenario.yaml")
        val uuidFile = File("/tmp/koncerto-demo/issue-save-scenario.yaml")
        val identFile = File("/tmp/koncerto-demo/SAVE-1-scenario.yaml")
        assertThat(uuidFile.exists()).isEqualTo(true)
        assertThat(identFile.exists()).isEqualTo(true)
        assertThat(uuidFile.readText()).isEqualTo(identFile.readText())
        assertThat(uuidFile.readText().contains("action: click")).isEqualTo(true)
    }

    @Test
    fun `generate returns null when output has no demo_scenario block`(@TempDir tmpDir: java.nio.file.Path) = runTest {
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

    @Test
    fun `generate falls back to third model when first two fail`(@TempDir tmpDir: java.nio.file.Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-5", identifier = "T-5", title = "Feature", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val calledModels = mutableListOf<String>()
        val runner = DemoScenarioGenerator.ProcessRunner { cmd, _, _ ->
            val model = cmd[cmd.indexOf("--model") + 1]
            calledModels += model
            if (model == FreeModelCycler.DEFAULT_FREE_MODELS[2]) validScenarioOutput else null
        }
        val result = generatorWithRunner(runner).generate(issue, workspace)
        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(FreeModelCycler.DEFAULT_FREE_MODELS.take(3))
    }

    private fun initGitRepoWithDiff(tmpDir: Path, changeContent: String) {
        runProcess(listOf("git", "init"), tmpDir)
        runProcess(listOf("git", "config", "user.email", "test@example.com"), tmpDir)
        runProcess(listOf("git", "config", "user.name", "Test User"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), "initial content")
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "initial"), tmpDir)
        runProcess(listOf("git", "checkout", "-b", "feature"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), changeContent)
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "feature"), tmpDir)
    }

    private fun runProcess(command: List<String>, workDir: Path) {
        val process = ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectErrorStream(true)
            .start()
        process.waitFor()
        check(process.exitValue() == 0) { "Command failed: ${command.joinToString(" ")}" }
    }

    @Test
    fun `generate saves scenario files when model output is valid`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "T-1", createdNow = true)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-save", identifier = "SAVE-1", title = "Save scenario", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val gen = DemoScenarioGenerator(
            opencodeCommand = "echo",
            logger = logger(),
            processRunner = { _, _, _ ->
                """
                demo_scenario:
                  description: "Saved scenario"
                  steps:
                    - action: wait
                      ms: 500
                """.trimIndent()
            }
        )
        val path = gen.generate(issue, workspace)
        assertThat(path).isNotNull()
        assertThat(File(path!!).exists()).isEqualTo(true)
    }

    @Test
    fun `buildPrompt includes git diff when repository has changes`(@TempDir tmpDir: Path) {
        initGitRepoWithDiff(tmpDir, "updated readme content")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "T-1", createdNow = true)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i-diff", identifier = "DIFF-1", title = "With diff", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("## PR Changes") || prompt.contains("updated readme")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt tolerates git diff failures for missing workspace`() {
        val missing = java.nio.file.Paths.get("/tmp/koncerto-missing-${System.nanoTime()}")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(missing, "T-1", createdNow = false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i-missing", identifier = "MISS-1", title = "Missing ws", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
    }

    @Test
    fun `defaultProcessRunner returns null on non-zero exit`() {
        val output = DemoScenarioGenerator.defaultProcessRunner().run(
            listOf("bash", "-lc", "exit 3"),
            File("/tmp"),
            2
        )
        assertThat(output).isNull()
    }

    @Test
    fun `defaultProcessRunner returns null on timeout`() {
        val output = DemoScenarioGenerator.defaultProcessRunner().run(
            listOf("bash", "-lc", "sleep 2"),
            File("/tmp"),
            1
        )
        assertThat(output).isNull()
    }

    @Test
    fun `defaultProcessRunner does not deadlock on output larger than the OS pipe buffer`() {
        // Writing stdout before draining it (the original bug) blocks the child process once
        // the pipe buffer (~64KB on macOS/Linux) fills, and waitFor() just spins until timeout —
        // this reproduces that with a real subprocess producing well over that amount.
        val output = DemoScenarioGenerator.defaultProcessRunner().run(
            listOf("bash", "-lc", "yes X | head -c 500000"),
            File("/tmp"),
            10
        )
        assertThat(output).isNotNull()
        assertThat(output!!.length >= 400_000).isEqualTo(true)
    }

    @Test
    fun `generate returns null when scenario directory cannot be created`(@TempDir tmpDir: Path) = runTest {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "bad/issue-id", identifier = "DIR-FAIL", title = "Dir fail", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val result = generatorWithRunner { _, _, _ -> validScenarioOutput }.generate(issue, workspace)
        assertThat(result).isNull()
    }
}
