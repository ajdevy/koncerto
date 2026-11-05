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
    fun `buildPrompt documents the resolve step and lists available credential keys`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "Register with email code", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace, listOf("TEST_EMAIL_INBOX", "TEST_EMAIL_IMAP_PASSWORD"))
        assertThat(prompt.contains("Available test credentials")).isEqualTo(true)
        assertThat(prompt.contains("- TEST_EMAIL_INBOX")).isEqualTo(true)
        assertThat(prompt.contains("- TEST_EMAIL_IMAP_PASSWORD")).isEqualTo(true)
        assertThat(prompt.contains("action: resolve")).isEqualTo(true)
        // The resolve guidance must steer the model to read connection params from env, not hardcode.
        assertThat(prompt.contains("NEVER hardcode a username")).isEqualTo(true)
        assertThat(prompt.contains("most RECENT message")).isEqualTo(true)
        // And it must steer the model to reference a credential (e.g. the email) as ${KEY} in browser
        // type steps rather than inventing a placeholder that won't match the inbox.
        assertThat(prompt.contains("\${TEST_EMAIL_INBOX}")).isEqualTo(true)
        assertThat(prompt.contains("Do NOT invent a placeholder")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt omits the credentials section when none are available`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Available test credentials")).isEqualTo(false)
    }

    @Test
    fun `buildPrompt includes the live DOM inventory section when provided`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val inventory = """[{"route":"/login","testids":["send-code-button"]}]"""
        val prompt = generator().buildPrompt(issue, workspace, emptyList(), inventory)
        assertThat(prompt.contains("Live UI Inventory")).isEqualTo(true)
        assertThat(prompt.contains("STRONGEST ground truth")).isEqualTo(true)
        assertThat(prompt.contains("\"route\":\"/login\"")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt omits the live DOM inventory section when absent or blank`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        assertThat(generator().buildPrompt(issue, workspace, emptyList(), null).contains("Live UI Inventory")).isEqualTo(false)
        assertThat(generator().buildPrompt(issue, workspace, emptyList(), "   ").contains("Live UI Inventory")).isEqualTo(false)
    }

    @Test
    fun `buildRepairPrompt threads the live DOM inventory into the base prompt`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildRepairPrompt(
            issue, workspace, "demo_scenario:\n  steps: []", "click missed", emptyList(),
            """[{"route":"/login"}]"""
        )
        assertThat(prompt.contains("Live UI Inventory")).isEqualTo(true)
        assertThat(prompt.contains("\"route\":\"/login\"")).isEqualTo(true)
    }

    @Test
    fun `buildPrompt grounds on origin_main when no local main ref exists`(@TempDir tmpDir: Path) {
        // Reproduces the deployed-workspace shape: a PR-branch checkout whose only main-line ref is
        // origin/main. runGitDiff must fall back to it, otherwise grounding silently no-ops.
        initGitRepoWithOriginMain(tmpDir, "id=\"send-code-button\"")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("Real Selectors")).isEqualTo(true)
        assertThat(prompt.contains("[id=\"send-code-button\"]")).isEqualTo(true)
    }

    @Test
    fun `saveCredentials stages a KEY=VALUE file and deleteCredentials removes it`() {
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-creds-save", identifier = "T-CREDS", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val gen = generator()
        val path = gen.saveCredentials(issue, linkedMapOf("A" to "1", "B" to "two"))
        try {
            assertThat(path).isNotNull()
            val body = java.io.File(path!!).readText()
            assertThat(body.contains("A=1")).isEqualTo(true)
            assertThat(body.contains("B=two")).isEqualTo(true)
            assertThat(java.io.File("/tmp/koncerto-demo/T-CREDS-credentials.env").exists()).isEqualTo(true)
        } finally {
            gen.deleteCredentials(issue)
        }
        assertThat(java.io.File("/tmp/koncerto-demo/issue-creds-save-credentials.env").exists()).isEqualTo(false)
        assertThat(java.io.File("/tmp/koncerto-demo/T-CREDS-credentials.env").exists()).isEqualTo(false)
    }

    @Test
    fun `saveCredentials returns null for an empty credential map`() {
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-empty-creds", identifier = "T-E", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        assertThat(generator().saveCredentials(issue, emptyMap())).isNull()
    }

    @Test
    fun `saveCredentials returns null when the file cannot be written`() {
        // An issue id containing a path separator points at a non-existent subdirectory, so the
        // write throws and is swallowed to null.
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "nested/does-not-exist", identifier = "T-BAD", title = "t", description = "d",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        assertThat(generator().saveCredentials(issue, mapOf("A" to "1"))).isNull()
    }

    @Test
    fun `buildRepairPrompt includes the prior scenario, the failure reason and the base prompt`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-1", identifier = "T-1",
            title = "Email login", description = "Send code and verify",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prior = "demo_scenario:\n  steps:\n    - action: click\n      selector: \"[data-testid=\\\"nope\\\"]\""
        val reason = "click target not found: [data-testid=\"nope\"]"
        val prompt = generator().buildRepairPrompt(issue, workspace, prior, reason)

        assertThat(prompt.contains("The previous demo scenario FAILED")).isEqualTo(true)
        assertThat(prompt.contains(reason)).isEqualTo(true)
        assertThat(prompt.contains("data-testid=\\\"nope\\\"")).isEqualTo(true)
        // Must still carry the base prompt (issue context + instructions).
        assertThat(prompt.contains("Email login")).isEqualTo(true)
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

    // ---- API (server-side) scenarios -------------------------------------------------------

    private fun apiIssue(id: String = "issue-api", identifier: String = "T-API") =
        com.flexsentlabs.koncerto.core.model.Issue(
            id = id, identifier = identifier, title = "Add items endpoint",
            description = "POST /api/items creates an item",
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )

    private val validApiScenarioOutput = """
        Here is your API scenario:
        api_scenario:
          description: "Items API"
          steps:
            - action: request
              method: GET
              path: /api/items
              expect_status: 200
    """.trimIndent()

    @Test
    fun `extractScenarioBlock parses an unfenced api_scenario block`() {
        val result = generator().extractScenarioBlock(validApiScenarioOutput, "api_scenario")

        assertThat(result).isNotNull()
        assertThat(result!!.startsWith("api_scenario:")).isEqualTo(true)
        assertThat(result.contains("path: /api/items")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock parses an unlabeled fence whose content starts with api_scenario`() {
        val raw = "Sure.\n\n```yaml\napi_scenario:\n  description: \"d\"\n  steps:\n    - action: request\n      path: /a\n```"

        val result = generator().extractScenarioBlock(raw, "api_scenario")

        assertThat(result).isNotNull()
        assertThat(result!!.startsWith("api_scenario:")).isEqualTo(true)
        assertThat(result.contains("path: /a")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock wraps a labeled api_scenario fence under the root key`() {
        val raw = "```yaml api_scenario\ndescription: \"d\"\nsteps:\n  - action: request\n    path: /a\n```"

        val result = generator().extractScenarioBlock(raw, "api_scenario")!!

        assertThat(result.startsWith("api_scenario:\n  description")).isEqualTo(true)
        assertThat(result.contains("  steps:")).isEqualTo(true)
    }

    @Test
    fun `extractScenarioBlock does not confuse the two scenario kinds`() {
        // A browser scenario must not satisfy an API extraction, or a UI ticket would be staged as
        // a server-side demo and recorded with the wrong recorder.
        assertThat(generator().extractScenarioBlock(validScenarioOutput, "api_scenario")).isNull()
        assertThat(generator().extractScenarioBlock(validApiScenarioOutput, "demo_scenario")).isNull()
    }

    @Test
    fun `extractScenarioBlock returns null for an empty api_scenario section`() {
        assertThat(generator().extractScenarioBlock("api_scenario:\n", "api_scenario")).isNull()
        assertThat(generator().extractScenarioBlock("```yaml api_scenario\n\n```", "api_scenario")).isNull()
    }

    @Test
    fun `buildApiPrompt includes the issue and the live endpoint inventory`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val inventory = """{"routes":[{"method":"POST","path":"/api/items"}]}"""

        val prompt = generator().buildApiPrompt(apiIssue(), workspace, emptyList(), inventory)

        assertThat(prompt.contains("Add items endpoint")).isEqualTo(true)
        assertThat(prompt.contains("POST /api/items creates an item")).isEqualTo(true)
        assertThat(prompt.contains("Live Endpoint Inventory")).isEqualTo(true)
        assertThat(prompt.contains("\"path\":\"/api/items\"")).isEqualTo(true)
        assertThat(prompt.contains("Generate the api_scenario YAML now.")).isEqualTo(true)
    }

    @Test
    fun `buildApiPrompt omits the endpoint inventory when absent or blank`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        assertThat(generator().buildApiPrompt(apiIssue(), workspace, emptyList(), null)
            .contains("Live Endpoint Inventory")).isEqualTo(false)
        assertThat(generator().buildApiPrompt(apiIssue(), workspace, emptyList(), "  ")
            .contains("Live Endpoint Inventory")).isEqualTo(false)
    }

    @Test
    fun `buildApiPrompt lists credential keys as interpolation references and never values`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val withCreds = generator().buildApiPrompt(apiIssue(), workspace, listOf("API_TOKEN"), null)
        assertThat(withCreds.contains("- API_TOKEN")).isEqualTo(true)
        assertThat(withCreds.contains("{{API_TOKEN}}")).isEqualTo(true)
        assertThat(withCreds.contains("Never write a literal secret")).isEqualTo(true)

        assertThat(generator().buildApiPrompt(apiIssue(), workspace, emptyList(), null)
            .contains("Available test credentials")).isEqualTo(false)
    }

    @Test
    fun `buildApiPrompt reads its system prompt from koncerto's own workflow dir`(
        @TempDir tmpDir: Path,
        @TempDir workflowDir: Path
    ) {
        val promptsDir = workflowDir.resolve("prompts").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(promptsDir.resolve("demo-scenario-api.md"), "You are an API demo generator.")
        val cache = com.flexsentlabs.koncerto.workflow.WorkflowCache()
        cache.setWorkflowDir(workflowDir)
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val gen = DemoScenarioGenerator(opencodeCommand = "opencode", logger = logger(), workflowCache = cache)

        assertThat(gen.buildApiPrompt(apiIssue(), workspace).contains("You are an API demo generator.")).isEqualTo(true)
    }

    @Test
    fun `generateApi stages the scenario under both keys and leaves the browser scenario alone`(
        @TempDir tmpDir: Path
    ) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-api-save", identifier = "T-APISAVE")
        val browser = java.io.File("/tmp/koncerto-demo/${issue.id}-scenario.yaml")
        val gen = generatorWithRunner { _, _, _ -> validApiScenarioOutput }
        try {
            browser.parentFile.mkdirs()
            browser.writeText("demo_scenario:\n  steps: []\n")

            val path = gen.generateApi(issue, workspace)

            assertThat(path).isNotNull()
            assertThat(path!!.endsWith("issue-api-save-api-scenario.yaml")).isEqualTo(true)
            assertThat(java.io.File(path).readText().contains("api_scenario:")).isEqualTo(true)
            assertThat(java.io.File("/tmp/koncerto-demo/T-APISAVE-api-scenario.yaml").exists()).isEqualTo(true)
            // Generation alone must not clear the browser scenario — only the caller does that,
            // and only once the API scenario is safely written.
            assertThat(browser.exists()).isEqualTo(true)
        } finally {
            browser.delete()
            java.io.File("/tmp/koncerto-demo/issue-api-save-api-scenario.yaml").delete()
            java.io.File("/tmp/koncerto-demo/T-APISAVE-api-scenario.yaml").delete()
        }
    }

    @Test
    fun `generateApi returns null when no model produces a usable scenario`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        assertThat(generatorWithRunner { _, _, _ -> "no scenario here" }
            .generateApi(apiIssue(), workspace)).isNull()
        assertThat(generatorWithRunner { _, _, _ -> null }
            .generateApi(apiIssue(), workspace)).isNull()
    }

    @Test
    fun `buildApiPrompt includes the PR diff when the workspace has one`(@TempDir tmpDir: Path) {
        initGitRepoWithOriginMain(tmpDir, "fun createItem() = 201")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val prompt = generator().buildApiPrompt(apiIssue(), workspace)

        assertThat(prompt.contains("## PR Changes")).isEqualTo(true)
        assertThat(prompt.contains("fun createItem() = 201")).isEqualTo(true)
    }

    @Test
    fun `generateApi returns null when the scenario cannot be staged`(@TempDir tmpDir: Path) {
        // A directory sitting where the scenario file belongs makes the write fail — the same shape
        // as a permissions or disk problem, which must degrade to null rather than throw into the
        // pipeline.
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-api-blocked", identifier = "T-APIBLOCK")
        val blocker = java.io.File("/tmp/koncerto-demo/issue-api-blocked-api-scenario.yaml")
        try {
            blocker.mkdirs()

            val path = generatorWithRunner { _, _, _ -> validApiScenarioOutput }.generateApi(issue, workspace)

            assertThat(path).isNull()
        } finally {
            blocker.deleteRecursively()
            java.io.File("/tmp/koncerto-demo/T-APIBLOCK-api-scenario.yaml").delete()
        }
    }

    @Test
    fun `deleteBrowserScenario removes both keys and tolerates absent files`() {
        val issue = apiIssue(id = "issue-del", identifier = "T-DEL")
        val byId = java.io.File("/tmp/koncerto-demo/issue-del-scenario.yaml")
        val byIdent = java.io.File("/tmp/koncerto-demo/T-DEL-scenario.yaml")
        byId.parentFile.mkdirs()
        byId.writeText("demo_scenario:")
        byIdent.writeText("demo_scenario:")

        generator().deleteBrowserScenario(issue)

        assertThat(byId.exists()).isEqualTo(false)
        assertThat(byIdent.exists()).isEqualTo(false)
        // Idempotent: a second call on already-absent files must not throw.
        generator().deleteBrowserScenario(issue)
    }

    @Test
    fun `generateApi rejects a block that carries no request`() {
        // The caller deletes the browser scenario on a non-null return, so a block that merely has
        // the right root key must not count as success.
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(
            java.nio.file.Files.createTempDirectory("api-unusable"), "key", false)
        val issue = apiIssue(id = "issue-api-unusable", identifier = "T-APIUNUSABLE")
        val gen = generatorWithRunner { _, _, _ -> "api_scenario:\n  description: \"no steps at all\"\n" }
        try {
            assertThat(gen.generateApi(issue, workspace)).isNull()
            assertThat(java.io.File("/tmp/koncerto-demo/issue-api-unusable-api-scenario.yaml").exists()).isEqualTo(false)
        } finally {
            gen.deleteApiScenario(issue)
        }
    }

    @Test
    fun `carriesRequest accepts a real scenario and rejects a shape-only one`() {
        assertThat(generator().carriesRequest(validApiScenarioOutput)).isEqualTo(true)
        assertThat(generator().carriesRequest("api_scenario:\n  description: \"d\"\n")).isEqualTo(false)
        assertThat(generator().carriesRequest("api_scenario:\n  steps:\n    - action: note\n      note: hi\n")).isEqualTo(false)
    }

    @Test
    fun `repairApi regenerates the API scenario and feeds back the failure`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-api-repair", identifier = "T-APIREPAIR")
        var seenPrompt: String? = null
        val gen = generatorWithRunner { command, _, _ -> seenPrompt = command.last(); validApiScenarioOutput }
        try {
            val path = gen.repairApi(
                issue, workspace,
                priorScenario = "api_scenario:\n  steps:\n    - action: request\n      path: /wrong\n",
                failureReason = "expected 200 got 404",
                openApiInventory = """{"routes":[{"method":"GET","path":"/api/items"}]}"""
            )

            assertThat(path).isNotNull()
            assertThat(path!!.endsWith("issue-api-repair-api-scenario.yaml")).isEqualTo(true)
            // The repair prompt must carry both why it failed and what failed, or the model just
            // re-emits the same broken scenario.
            assertThat(seenPrompt!!.contains("expected 200 got 404")).isEqualTo(true)
            assertThat(seenPrompt!!.contains("/wrong")).isEqualTo(true)
            assertThat(seenPrompt!!.contains("api_scenario")).isEqualTo(true)
        } finally {
            gen.deleteApiScenario(issue)
        }
    }

    @Test
    fun `repairApi returns null when the model fails or emits an unusable block`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-api-repair-fail", identifier = "T-APIREPAIRFAIL")

        assertThat(generatorWithRunner { _, _, _ -> null }
            .repairApi(issue, workspace, "prior", "boom")).isNull()
        assertThat(generatorWithRunner { _, _, _ -> "api_scenario:\n  description: \"nothing\"\n" }
            .repairApi(issue, workspace, "prior", "boom")).isNull()
    }

    @Test
    fun `deleteApiScenario removes both keys and tolerates absent files`() {
        val issue = apiIssue(id = "issue-api-del", identifier = "T-APIDEL")
        val byId = java.io.File("/tmp/koncerto-demo/issue-api-del-api-scenario.yaml")
        val byIdent = java.io.File("/tmp/koncerto-demo/T-APIDEL-api-scenario.yaml")
        byId.parentFile.mkdirs()
        byId.writeText("api_scenario:")
        byIdent.writeText("api_scenario:")

        generator().deleteApiScenario(issue)

        assertThat(byId.exists()).isEqualTo(false)
        assertThat(byIdent.exists()).isEqualTo(false)
        generator().deleteApiScenario(issue)
    }

    private val validAndroidScenarioOutput = """
        android_scenario:
          appId: com.example.app
          description: "Sign in"
          steps:
            - action: launch
            - action: tap
              text: "Sign in"
    """.trimIndent()

    @Test
    fun `generateAndroid stages under both keys and pins the detected application id`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-android", identifier = "T-ANDROID")
        var seenPrompt: String? = null
        val gen = generatorWithRunner { command, _, _ -> seenPrompt = command.last(); validAndroidScenarioOutput }
        try {
            val path = gen.generateAndroid(issue, workspace, applicationId = "com.example.demoapp")

            assertThat(path).isNotNull()
            assertThat(path!!.endsWith("issue-android-android-scenario.yaml")).isEqualTo(true)
            assertThat(java.io.File("/tmp/koncerto-demo/T-ANDROID-android-scenario.yaml").exists()).isEqualTo(true)
            // A wrong appId is the one error Maestro cannot recover from, so it is injected rather
            // than left to the model.
            assertThat(seenPrompt!!.contains("com.example.demoapp")).isEqualTo(true)
            assertThat(seenPrompt!!.contains("do not infer another one")).isEqualTo(true)
        } finally {
            gen.deleteAndroidScenario(issue)
        }
    }

    @Test
    fun `generateAndroid rejects a block with no appId or nothing to do`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = apiIssue(id = "issue-android-bad", identifier = "T-ANDROIDBAD")

        assertThat(generatorWithRunner { _, _, _ -> "android_scenario:\n  steps:\n    - action: launch\n" }
            .generateAndroid(issue, workspace)).isNull()
        assertThat(generatorWithRunner { _, _, _ -> "android_scenario:\n  appId: com.x\n  steps:\n    - action: wait\n" }
            .generateAndroid(issue, workspace)).isNull()
        assertThat(generatorWithRunner { _, _, _ -> null }.generateAndroid(issue, workspace)).isNull()
    }

    @Test
    fun `carriesAndroidStep needs an appId and a non-wait action`() {
        assertThat(generator().carriesAndroidStep(validAndroidScenarioOutput)).isEqualTo(true)
        assertThat(generator().carriesAndroidStep("android_scenario:\n  appId:\n  steps:\n    - action: tap\n")).isEqualTo(false)
        assertThat(generator().carriesAndroidStep("android_scenario:\n  appId: com.x\n  steps:\n    - action: wait\n")).isEqualTo(false)
    }

    @Test
    fun `buildAndroidPrompt omits optional sections when nothing is available`(@TempDir tmpDir: Path) {
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val bare = generator().buildAndroidPrompt(apiIssue(), workspace)
        assertThat(bare.contains("Application id")).isEqualTo(false)
        assertThat(bare.contains("Available test credentials")).isEqualTo(false)
        assertThat(bare.contains("Generate the android_scenario YAML now.")).isEqualTo(true)

        val withCreds = generator().buildAndroidPrompt(apiIssue(), workspace, null, listOf("TEST_LOGIN"))
        assertThat(withCreds.contains("- TEST_LOGIN")).isEqualTo(true)
    }

    @Test
    fun `buildAndroidPrompt includes the diff when the workspace has one`(@TempDir tmpDir: Path) {
        initGitRepoWithOriginMain(tmpDir, "id=\"sign-in-button\"")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val prompt = generator().buildAndroidPrompt(apiIssue(), workspace)

        assertThat(prompt.contains("## PR Changes")).isEqualTo(true)
        assertThat(prompt.contains("sign-in-button")).isEqualTo(true)
    }

    @Test
    fun `deleteAndroidScenario removes both keys and tolerates absent files`() {
        val issue = apiIssue(id = "issue-android-del", identifier = "T-ANDROIDDEL")
        val byId = java.io.File("/tmp/koncerto-demo/issue-android-del-android-scenario.yaml")
        val byIdent = java.io.File("/tmp/koncerto-demo/T-ANDROIDDEL-android-scenario.yaml")
        byId.parentFile.mkdirs(); byId.writeText("android_scenario:"); byIdent.writeText("android_scenario:")

        generator().deleteAndroidScenario(issue)

        assertThat(byId.exists()).isEqualTo(false)
        assertThat(byIdent.exists()).isEqualTo(false)
        generator().deleteAndroidScenario(issue)
    }

    @Test
    fun `buildAndroidPrompt reads its system prompt from koncerto's own workflow dir`(
        @TempDir tmpDir: Path,
        @TempDir workflowDir: Path
    ) {
        val promptsDir = workflowDir.resolve("prompts").also { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(promptsDir.resolve("demo-scenario-android.md"), "You are a mobile demo generator.")
        val cache = com.flexsentlabs.koncerto.workflow.WorkflowCache()
        cache.setWorkflowDir(workflowDir)
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)

        val gen = DemoScenarioGenerator(opencodeCommand = "opencode", logger = logger(), workflowCache = cache)

        assertThat(gen.buildAndroidPrompt(apiIssue(), workspace).contains("You are a mobile demo generator.")).isEqualTo(true)
    }

    @Test
    fun `the staging suffixes match the reader side convention`() {
        assertThat(DemoScenarioGenerator.BROWSER_SCENARIO_SUFFIX).isEqualTo("-scenario.yaml")
        assertThat(DemoScenarioGenerator.API_SCENARIO_SUFFIX).isEqualTo("-api-scenario.yaml")
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
    fun `buildPrompt lists real routes extracted from the diff`(@TempDir tmpDir: Path) {
        initGitRepoWithDiff(tmpDir, "@app.get(\"/dashboard\")\nasync def dashboard():\n    pass\n")
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i-routes", identifier = "T-9", title = "Add dashboard", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("## Real Routes (extracted from the actual diff)")).isEqualTo(true)
        assertThat(prompt.contains("/dashboard")).isEqualTo(true)
    }

    @Test
    fun `extractRealSelectors pulls testid, id, name and aria-label from added lines only`() {
        val diff = """
            diff --git a/app/main.py b/app/main.py
            +++ b/app/main.py
            -<button data-testid="old-button">Old</button>
            +<input id="email" name="email" data-testid="email-input" aria-label="Email address">
            +<button data-testid="send-code-button">Send</button>
             <p>unchanged context line</p>
        """.trimIndent()
        val selectors = generator().extractRealSelectors(diff)
        assertThat(selectors.contains("[data-testid=\"email-input\"]")).isEqualTo(true)
        assertThat(selectors.contains("[data-testid=\"send-code-button\"]")).isEqualTo(true)
        assertThat(selectors.contains("[id=\"email\"]")).isEqualTo(true)
        assertThat(selectors.contains("[name=\"email\"]")).isEqualTo(true)
        assertThat(selectors.contains("[aria-label=\"Email address\"]")).isEqualTo(true)
        assertThat(selectors.contains("[data-testid=\"old-button\"]")).isEqualTo(false)
    }

    @Test
    fun `extractRealRoutes pulls FastAPI and Express style route registrations from added lines only`() {
        val diff = """
            diff --git a/app/main.py b/app/main.py
            +++ b/app/main.py
            -@app.get("/old-route")
            +@app.get("/login")
            +async def login_page():
            +    pass
            +
            +router.post('/api/verify', handler)
        """.trimIndent()
        val routes = generator().extractRealRoutes(diff)
        assertThat(routes.contains("/login")).isEqualTo(true)
        assertThat(routes.contains("/api/verify")).isEqualTo(true)
        assertThat(routes.contains("/old-route")).isEqualTo(false)
    }

    @Test
    fun `ensureNavigatesToRealRoute injects a navigate step when the model's scenario never leaves the root`() {
        // Regression test: a sample free-tier scenario for DEMO-52 used correct real selectors
        // (data-testid=send-code-button etc.) but never included a navigate step to /login at
        // all — it just asserted on #email-step from the landing page, which doesn't have it.
        // Every subsequent click/type/assert then silently failed to find its target, and the
        // recorded video never showed the feature despite the selectors being right.
        val block = """
            demo_scenario:
              description: "Email code login"
              steps:
                - action: wait
                  ms: 500
                - action: click
                  selector: "[data-testid='send-code-button']"
        """.trimIndent()
        val grounded = generator().ensureNavigatesToRealRoute(block, listOf("/login"))
        assertThat(grounded.contains("action: navigate")).isEqualTo(true)
        assertThat(grounded.contains("url: \"/login\"")).isEqualTo(true)
        // The injected navigate must come before the pre-existing steps, not after.
        assertThat(grounded.indexOf("action: navigate") < grounded.indexOf("action: wait")).isEqualTo(true)
    }

    @Test
    fun `ensureNavigatesToRealRoute leaves the scenario untouched when it already navigates off-root`() {
        val block = """
            demo_scenario:
              description: "Email code login"
              steps:
                - action: navigate
                  url: "/login"
                - action: click
                  selector: "[data-testid='send-code-button']"
        """.trimIndent()
        val grounded = generator().ensureNavigatesToRealRoute(block, listOf("/login"))
        assertThat(grounded).isEqualTo(block)
    }

    @Test
    fun `ensureNavigatesToRealRoute is a no-op when no real routes were extracted`() {
        val block = """
            demo_scenario:
              description: "Landing page only"
              steps:
                - action: scroll
                  direction: down
                  amount: 300
        """.trimIndent()
        val grounded = generator().ensureNavigatesToRealRoute(block, emptyList())
        assertThat(grounded).isEqualTo(block)
    }

    @Test
    fun `buildPrompt lists real selectors even when they fall past the truncated diff display`(@TempDir tmpDir: Path) {
        // Regression test: a real ~87KB diff for DEMO-52 had its login form's data-testid
        // attributes starting at byte offset ~8171 — just past the old 8000-char display
        // cutoff — so the model never saw them in the raw "## PR Changes" text and invented
        // non-existent selectors instead. extractRealSelectors must run on the FULL diff so
        // real attributes surface regardless of where they land in a large diff.
        // runGitDiff() hard-codes a diff against "main" — explicitly name the initial branch
        // so this doesn't depend on the ambient git client's init.defaultBranch config (which
        // differs between local machines and CI runners, e.g. "master" vs "main").
        runProcess(listOf("git", "init", "-b", "main"), tmpDir)
        runProcess(listOf("git", "config", "user.email", "test@example.com"), tmpDir)
        runProcess(listOf("git", "config", "user.name", "Test User"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), "initial content")
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "initial"), tmpDir)
        runProcess(listOf("git", "checkout", "-b", "feature"), tmpDir)
        // Pad past the 8000-char display cutoff before the real markup, mirroring the real diff.
        val padding = "// filler line to push real content past the truncation cutoff\n".repeat(200)
        java.nio.file.Files.writeString(
            tmpDir.resolve("README.md"),
            padding + "<button data-testid=\"send-code-button\">Send</button>\n"
        )
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "feature"), tmpDir)

        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Add login", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("## Real Selectors")).isEqualTo(true)
        assertThat(prompt.contains("[data-testid=\"send-code-button\"]")).isEqualTo(true)
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
    fun `generate tries the next model when a response can't be parsed into a scenario`(@TempDir tmpDir: java.nio.file.Path) = runTest {
        // A model responding with something unparseable must not be treated the same as it
        // failing to respond at all — it should still fall through to the next model rather
        // than giving up on the very first response received.
        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "issue-6", identifier = "T-6", title = "Feature", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val calledModels = mutableListOf<String>()
        val runner = DemoScenarioGenerator.ProcessRunner { cmd, _, _ ->
            val model = cmd[cmd.indexOf("--model") + 1]
            calledModels += model
            if (model == FreeModelCycler.DEFAULT_FREE_MODELS[0]) "Sorry, I cannot help with that." else validScenarioOutput
        }
        val result = generatorWithRunner(runner).generate(issue, workspace)
        assertThat(result).isNotNull()
        assertThat(calledModels).isEqualTo(FreeModelCycler.DEFAULT_FREE_MODELS.take(2))
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
        // runGitDiff() hard-codes a diff against "main" — pin the initial branch explicitly so
        // this doesn't depend on the ambient git client's init.defaultBranch config, which
        // differs between environments (observed: passes locally, fails on the CI runner).
        runProcess(listOf("git", "init", "-b", "main"), tmpDir)
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

    private fun initGitRepoWithOriginMain(tmpDir: Path, changeContent: String) {
        // Build a clone-like checkout on a PR branch whose ONLY main-line ref is origin/main:
        // create a bare "remote", push main to it, branch to feature with the change, then delete
        // the local main branch. `git diff main...HEAD` then fails; `git diff origin/main...HEAD`
        // works — exactly the deployed-workspace shape.
        val remote = tmpDir.resolveSibling(tmpDir.fileName.toString() + "-remote.git")
        java.nio.file.Files.createDirectories(remote)
        runProcess(listOf("git", "init", "--bare", "-b", "main"), remote)
        runProcess(listOf("git", "init", "-b", "main"), tmpDir)
        runProcess(listOf("git", "config", "user.email", "test@example.com"), tmpDir)
        runProcess(listOf("git", "config", "user.name", "Test User"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), "initial content")
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "initial"), tmpDir)
        runProcess(listOf("git", "remote", "add", "origin", remote.toString()), tmpDir)
        runProcess(listOf("git", "push", "origin", "main"), tmpDir)
        runProcess(listOf("git", "checkout", "-b", "feature"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), changeContent)
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "feature"), tmpDir)
        // Drop the local main so only the remote-tracking origin/main remains.
        runProcess(listOf("git", "branch", "-D", "main"), tmpDir)
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
    fun `buildPrompt does not leak git error text as diff content when main branch is absent`(@TempDir tmpDir: Path) {
        // Regression test: runGitDiff hard-codes `git diff main...HEAD`. Merging stderr into
        // stdout meant a checkout whose default branch ISN'T "main" (e.g. "master") produced a
        // non-blank "fatal: ambiguous argument 'main...HEAD'..." string that isBlank() alone
        // didn't catch, so that error text got treated as if it were the real diff — silently
        // reintroducing the exact selector-invention failure the full-diff extraction was
        // built to prevent, for any target project not on a branch literally named "main".
        runProcess(listOf("git", "init", "-b", "master"), tmpDir)
        runProcess(listOf("git", "config", "user.email", "test@example.com"), tmpDir)
        runProcess(listOf("git", "config", "user.name", "Test User"), tmpDir)
        java.nio.file.Files.writeString(tmpDir.resolve("README.md"), "initial")
        runProcess(listOf("git", "add", "README.md"), tmpDir)
        runProcess(listOf("git", "commit", "-m", "initial"), tmpDir)

        val workspace = com.flexsentlabs.koncerto.workspace.Workspace(tmpDir, "key", false)
        val issue = com.flexsentlabs.koncerto.core.model.Issue(
            id = "i1", identifier = "T-1", title = "Add login", description = null,
            priority = 1, state = "Todo", branchName = null, url = null,
            labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        val prompt = generator().buildPrompt(issue, workspace)
        assertThat(prompt.contains("fatal:")).isEqualTo(false)
        assertThat(prompt.contains("ambiguous argument")).isEqualTo(false)
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
