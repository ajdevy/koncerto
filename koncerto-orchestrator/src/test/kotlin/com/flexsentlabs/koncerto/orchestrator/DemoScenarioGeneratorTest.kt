package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
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
        assertThat(prompt.contains("Generate the demo_scenario YAML now.")).isEqualTo(true)
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
        assertThat(calledModels).isEqualTo(listOf("opencode-free-1"))
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
            if (model == "opencode-free-1") null else validScenarioOutput
        }
        val gen = generatorWithRunner(runner)
        val result = gen.generate(issue, workspace)

        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(listOf("opencode-free-1", "opencode-free-2"))
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
}
