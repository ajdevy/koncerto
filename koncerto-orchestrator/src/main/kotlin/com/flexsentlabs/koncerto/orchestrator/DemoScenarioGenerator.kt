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

    fun generate(
        issue: Issue,
        workspace: Workspace,
        credentialKeys: List<String> = emptyList(),
        domInventory: String? = null
    ): String? {
        val prompt = buildPrompt(issue, workspace, credentialKeys, domInventory)
        val block = runWithFallback(prompt, workspace.path.toFile(), issue.id) ?: return null
        val routes = extractRealRoutes(runGitDiff(workspace.path.toFile()) ?: "")
        val grounded = ensureNavigatesToRealRoute(block, routes)
        return saveScenario(issue, grounded)
    }

    /**
     * Generates a SERVER-SIDE demo scenario: the `api_scenario` YAML the API recorder executes as
     * real curl calls against the deployed target.
     *
     * Used when the deployment turns out to have no browsable UI, where a browser recording would
     * only show an app with nothing to look at. [openApiInventory] is the compacted route inventory
     * read from the running service and is the strongest ground truth available — it is what stops
     * the model inventing endpoints, exactly as the DOM inventory does for the browser path.
     *
     * Returns the staged scenario path, or null when no model produced a usable scenario. A null
     * return leaves the browser scenario untouched, so the caller can fall back to it.
     */
    fun generateApi(
        issue: Issue,
        workspace: Workspace,
        credentialKeys: List<String> = emptyList(),
        openApiInventory: String? = null
    ): String? {
        val prompt = buildApiPrompt(issue, workspace, credentialKeys, openApiInventory)
        val block = runWithFallback(prompt, workspace.path.toFile(), issue.id, API_ROOT_KEY) ?: return null
        if (!carriesRequest(block)) {
            // Staging is what makes the caller delete the browser scenario, so a block that merely
            // has the right root key must not count as success — that would trade a working browser
            // demo for a scenario the recorder cannot execute.
            logger.warn("demo_api_scenario_unusable", mapOf("issue_id" to issue.id))
            return null
        }
        return saveApiScenario(issue, block)
    }

    /**
     * Regenerates an API scenario after a failed recording — the API counterpart of [repair].
     *
     * Without this, recovery on an API ticket ran the browser repair, which re-created the browser
     * scenario the API path had just removed, left the failing API scenario untouched, and re-ran it
     * unchanged until the cycle cap blocked the ticket.
     */
    fun repairApi(
        issue: Issue,
        workspace: Workspace,
        priorScenario: String,
        failureReason: String,
        credentialKeys: List<String> = emptyList(),
        openApiInventory: String? = null
    ): String? {
        val prompt = buildString {
            appendLine("The previous API demo scenario FAILED to record. Produce a corrected `api_scenario` YAML block.")
            appendLine()
            appendLine("## Why it failed")
            appendLine(failureReason.take(1500))
            appendLine()
            appendLine("## The scenario that failed")
            appendLine(priorScenario.take(3000))
            appendLine()
            appendLine("Fix the cause: use only paths and methods the endpoint inventory below declares,")
            appendLine("assert the status the endpoint actually returns, and keep the steps that clearly")
            appendLine("worked. Then re-emit the full corrected scenario.")
            appendLine()
            append(buildApiPrompt(issue, workspace, credentialKeys, openApiInventory))
        }
        val block = runWithFallback(prompt, workspace.path.toFile(), issue.id, API_ROOT_KEY) ?: return null
        if (!carriesRequest(block)) {
            logger.warn("demo_api_scenario_unusable", mapOf("issue_id" to issue.id))
            return null
        }
        logger.info("demo_api_scenario_repaired", mapOf("issue_id" to issue.id))
        return saveApiScenario(issue, block)
    }

    /** Whether an extracted `api_scenario` block actually contains an executable request step. */
    internal fun carriesRequest(block: String): Boolean {
        if (!block.lineSequence().any { it.trim().startsWith("steps:") }) return false
        return block.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith("path:") || (t.startsWith("- ") && t.contains("path:"))
        }
    }

    internal fun buildApiPrompt(
        issue: Issue,
        workspace: Workspace,
        credentialKeys: List<String> = emptyList(),
        openApiInventory: String? = null
    ): String {
        // Same resolution rule as the browser prompt: this template is koncerto's own, so it must
        // resolve against koncerto's workflow dir, not the target project's workspace.
        val systemPromptFile = workflowCache?.workflowDir?.resolve("prompts/demo-scenario-api.md")?.toFile()
            ?: workspace.path.resolve("prompts/demo-scenario-api.md").toFile()
        val systemPrompt = if (systemPromptFile.exists()) systemPromptFile.readText() else ""
        val diff = (runGitDiff(workspace.path.toFile()) ?: "").take(8000)

        return buildString {
            if (systemPrompt.isNotBlank()) {
                appendLine(systemPrompt)
                appendLine()
            }
            appendLine("## Issue")
            appendLine("${issue.title}: ${issue.description ?: ""}")
            appendLine()
            if (!openApiInventory.isNullOrBlank()) {
                appendLine("## Live Endpoint Inventory (read from the actually-deployed service)")
                appendLine("These are the endpoints the running service declares, with their parameters, request")
                appendLine("body fields and documented status codes. Author requests using ONLY these paths and")
                appendLine("methods. If a path is not listed here it does not exist — do not invent it. For")
                appendLine("`expect_status`, use the SUCCESS code the operation documents (2xx) unless the ticket")
                appendLine("explicitly calls for an error case — asserting a documented 4xx on the happy path")
                appendLine("produces a green demo that only proves the endpoint rejects input.")
                appendLine(openApiInventory.trim().take(MAX_INVENTORY_CHARS))
                appendLine()
            }
            if (diff.isNotBlank()) {
                appendLine("## PR Changes")
                appendLine(diff)
                appendLine()
            }
            if (credentialKeys.isNotEmpty()) {
                appendLine("## Available test credentials")
                appendLine("These credential KEYS are available to the recorder as environment variables (the")
                appendLine("VALUES are injected at run time and are NOT shown here):")
                credentialKeys.forEach { appendLine("- $it") }
                appendLine()
                appendLine("Reference one in a header or body the same way you reference a saved value, e.g.")
                appendLine("    - action: request")
                appendLine("      method: GET")
                appendLine("      path: /api/me")
                appendLine("      headers:")
                appendLine("        Authorization: \"Bearer {{API_TOKEN}}\"")
                appendLine("Never write a literal secret into the scenario — only the {{KEY}} reference.")
                appendLine()
            }
            append("Generate the api_scenario YAML now.")
        }
    }

    /**
     * Stages an API scenario under both keys the recorder looks for.
     *
     * Each file is written to a temp path and moved into place atomically: the reader decides the
     * platform purely on a file's existence, so a half-written file would route a run to the API
     * recorder and then fail to parse. If the second write fails, the first is rolled back — the
     * caller treats null as "nothing staged" and keeps the browser scenario, which would otherwise
     * leave both kinds present and the two sides disagreeing about the platform.
     */
    private fun saveApiScenario(issue: Issue, block: String): String? {
        val scenarioDir = java.nio.file.Paths.get(SCENARIO_DIR)
        val written = mutableListOf<java.nio.file.Path>()
        return try {
            java.nio.file.Files.createDirectories(scenarioDir)
            listOf(issue.id, issue.identifier).forEach { key ->
                val target = scenarioDir.resolve("$key$API_SCENARIO_SUFFIX")
                writeAtomically(target, block)
                written.add(target)
            }
            logger.info("demo_api_scenario_saved", mapOf(
                "issue_id" to issue.id,
                "issue_identifier" to issue.identifier
            ))
            written.first().toString()
        } catch (e: Exception) {
            written.forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
            logger.warn("demo_api_scenario_save_failed", mapOf(
                "issue_id" to issue.id, "error" to (e.message ?: "unknown")))
            null
        }
    }

    private fun writeAtomically(target: java.nio.file.Path, content: String) {
        // Temp file and target share a directory, so ATOMIC_MOVE is supported on every filesystem
        // this runs on. If it ever were not, the caller's catch rolls back and reports nothing
        // staged — which is the correct outcome anyway.
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        java.nio.file.Files.writeString(tmp, content)
        java.nio.file.Files.move(
            tmp, target,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    /**
     * Removes the API scenario staged for this issue.
     *
     * The reader checks for an API scenario first and unconditionally routes to the API recorder
     * when it finds one, so a file left by an earlier pass outranks a freshly generated browser
     * scenario for the rest of the issue's life. Cleanup has to run in both directions.
     */
    fun deleteApiScenario(issue: Issue) {
        val dir = java.nio.file.Paths.get(SCENARIO_DIR)
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.id}$API_SCENARIO_SUFFIX")) }
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.identifier}$API_SCENARIO_SUFFIX")) }
    }

    /**
     * Removes the browser scenario staged for this issue.
     *
     * Called only after an API scenario has been written: the recorder picks its platform from which
     * scenario kind is present, so leaving a stale browser scenario behind is harmless for routing
     * but misleading when debugging a run, and removing it before the API scenario exists would
     * strand the ticket with no scenario at all.
     */
    fun deleteBrowserScenario(issue: Issue) {
        val dir = java.nio.file.Paths.get(SCENARIO_DIR)
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.id}$BROWSER_SCENARIO_SUFFIX")) }
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.identifier}$BROWSER_SCENARIO_SUFFIX")) }
    }

    /**
     * Regenerates a scenario after a failed recording, feeding the model the scenario that just
     * failed plus why it failed so it can correct selectors/navigation/steps. This is the cheap,
     * scenario-first recovery step — it does not touch target code and needs no re-review.
     * Returns the saved scenario path, or null if the model could not produce a new scenario.
     */
    fun repair(
        issue: Issue,
        workspace: Workspace,
        priorScenario: String,
        failureReason: String,
        credentialKeys: List<String> = emptyList(),
        domInventory: String? = null
    ): String? {
        val prompt = buildRepairPrompt(issue, workspace, priorScenario, failureReason, credentialKeys, domInventory)
        val block = runWithFallback(prompt, workspace.path.toFile(), issue.id) ?: return null
        val routes = extractRealRoutes(runGitDiff(workspace.path.toFile()) ?: "")
        val grounded = ensureNavigatesToRealRoute(block, routes)
        logger.info("demo_scenario_repaired", mapOf("issue_id" to issue.id))
        return saveScenario(issue, grounded)
    }

    internal fun buildRepairPrompt(
        issue: Issue,
        workspace: Workspace,
        priorScenario: String,
        failureReason: String,
        credentialKeys: List<String> = emptyList(),
        domInventory: String? = null
    ): String {
        return buildString {
            appendLine("The previous demo scenario FAILED to record. Produce a corrected `demo_scenario` YAML block.")
            appendLine()
            appendLine("## Why it failed")
            appendLine(failureReason.take(1500))
            appendLine()
            appendLine("## The scenario that failed")
            appendLine(priorScenario.take(3000))
            appendLine()
            appendLine("Fix the cause: use only selectors/routes that exist in the diff below, reach the")
            appendLine("feature's page with `navigate` if it isn't linked from the landing page, and keep")
            appendLine("steps that clearly worked. Then re-emit the full corrected scenario.")
            appendLine()
            append(buildPrompt(issue, workspace, credentialKeys, domInventory))
        }
    }

    private fun runWithFallback(
        prompt: String,
        workDir: File,
        issueId: String,
        rootKey: String = "demo_scenario"
    ): String? {
        val models = FreeModelCycler.DEFAULT_FREE_MODELS
        // A custom scenario is mandatory (no demo is ever recorded without one), and real
        // free-tier latency for this prompt is highly bimodal in practice — anywhere from ~8s
        // to a full hang with no code-level difference between runs. Cycling through several
        // full passes buys many independent chances at a fast run instead of one long wait.
        for (pass in 1..PASS_COUNT) {
            for (model in models) {
                // Without this, opencode stops to ask permission for the Glob/Read/Write tool
                // calls it needs to inspect the diff and save the scenario file. There's no TTY
                // to approve from in this non-interactive invocation, so every real prompt hung.
                val cmd = opencodeCommand.split(" ") +
                    listOf("run", "--model", model, "--dangerously-skip-permissions", prompt)
                val output = processRunner.run(cmd, workDir, PER_ATTEMPT_TIMEOUT_SECONDS)
                if (output == null) {
                    logger.warn("demo_scenario_model_failed", mapOf("model" to model, "pass" to pass.toString()))
                    continue
                }
                val block = extractScenarioBlock(output, rootKey)
                if (block == null) {
                    // The model responded but didn't produce a parseable scenario — try the
                    // next model rather than giving up on the very first response we got.
                    logger.warn("demo_scenario_extract_failed", mapOf(
                        "issue_id" to issueId, "model" to model, "pass" to pass.toString()
                    ))
                    continue
                }
                return block
            }
        }
        logger.warn("demo_scenario_all_models_failed", mapOf(
            "models" to models.joinToString(","),
            "passes" to PASS_COUNT.toString()
        ))
        return null
    }

    private fun saveScenario(issue: Issue, block: String): String? {
        val scenarioDir = java.nio.file.Paths.get(SCENARIO_DIR)
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

    /**
     * Stages the effective demo credentials (secrets file merged with ticket-extracted values) as a
     * `KEY=VALUE` file next to the scenario, keyed by issue, so the recorder can pick them up by the
     * same file convention as the scenario and inject them into the recording container's env. Writes
     * nothing (returns null) when there are no credentials. Values are secret — never logged here.
     */
    fun saveCredentials(issue: Issue, credentials: Map<String, String>): String? {
        if (credentials.isEmpty()) return null
        val dir = java.nio.file.Paths.get(SCENARIO_DIR)
        return try {
            java.nio.file.Files.createDirectories(dir)
            val body = credentials.entries.joinToString("\n") { (k, v) -> "$k=$v" } + "\n"
            val uuidPath = dir.resolve("${issue.id}-credentials.env")
            java.nio.file.Files.writeString(uuidPath, body)
            java.nio.file.Files.writeString(dir.resolve("${issue.identifier}-credentials.env"), body)
            logger.info("demo_credentials_staged", mapOf(
                "issue_id" to issue.id, "keys" to credentials.keys.joinToString(",")))
            uuidPath.toString()
        } catch (e: Exception) {
            logger.warn("demo_credentials_stage_failed", mapOf(
                "issue_id" to issue.id, "error" to (e.message ?: "unknown")))
            null
        }
    }

    /** Removes the staged credentials files for an issue (best-effort; called after the demo run). */
    fun deleteCredentials(issue: Issue) {
        val dir = java.nio.file.Paths.get(SCENARIO_DIR)
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.id}-credentials.env")) }
        runCatching { java.nio.file.Files.deleteIfExists(dir.resolve("${issue.identifier}-credentials.env")) }
    }

    internal fun buildPrompt(
        issue: Issue,
        workspace: Workspace,
        credentialKeys: List<String> = emptyList(),
        domInventory: String? = null
    ): String {
        // The demo-scenario system prompt is a koncerto-provided template (like implement.md /
        // review.md), not something individual target projects ship — it must resolve relative
        // to koncerto's own workflow directory, not the target project's own workspace, or it's
        // silently never found and the model gets no formatting instructions at all.
        val systemPromptFile = workflowCache?.workflowDir?.resolve("prompts/demo-scenario.md")?.toFile()
            ?: workspace.path.resolve("prompts/demo-scenario.md").toFile()
        val systemPrompt = if (systemPromptFile.exists()) systemPromptFile.readText() else ""

        val readmeFile = workspace.path.resolve("README.md").toFile()
        val readme = if (readmeFile.exists()) readmeFile.readText().take(3000) else ""

        // Extract selectors from the FULL diff before truncating the displayed copy — a real
        // diff routinely exceeds the display budget, and the added markup's data-testid/id/name
        // attributes (the only ground truth the model has for what actually exists in the DOM)
        // can fall past that cutoff. Without this, the model invents plausible-looking selectors
        // for anything it can't see, and every step referencing them silently fails at
        // recording time.
        val fullDiff = runGitDiff(workspace.path.toFile()) ?: ""
        val realSelectors = extractRealSelectors(fullDiff)
        val realRoutes = extractRealRoutes(fullDiff)
        val diff = fullDiff.take(8000)

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
            if (realSelectors.isNotEmpty()) {
                appendLine("## Real Selectors (extracted from the actual diff)")
                appendLine("These are the ONLY selectors confirmed to exist in the new/changed markup. Use these exact selectors for click/type/assert/wait steps. Do not invent data-testid, id, name, or aria-label values that aren't listed here — a guessed selector matches nothing and the step silently fails.")
                realSelectors.forEach { appendLine("- $it") }
                appendLine()
            }
            if (realRoutes.isNotEmpty()) {
                appendLine("## Real Routes (extracted from the actual diff)")
                appendLine("These routes are confirmed to exist in the PR's changes. If the feature lives on one of these and it isn't reachable by clicking something on the landing page, use `navigate` with this exact relative URL as an early step — do not just click and hope, and do not silently stay on the landing page.")
                realRoutes.forEach { appendLine("- $it") }
                appendLine()
            }
            if (!domInventory.isNullOrBlank()) {
                appendLine("## Live UI Inventory (crawled from the actually-deployed app)")
                appendLine("This is the STRONGEST ground truth: routes and elements observed in the running app,")
                appendLine("not guessed from source. Author steps using ONLY the routes, data-testid values, form")
                appendLine("fields, and button/link text listed here. To reach a feature's page, use `navigate`")
                appendLine("with the exact route below rather than clicking a landing-page link and hoping. If a")
                appendLine("selector or route you want is not in this inventory, it does not exist — do not invent it.")
                appendLine(domInventory.trim())
                appendLine()
            }
            if (diff.isNotBlank()) {
                appendLine("## PR Changes")
                appendLine(diff)
                appendLine()
            }
            if (credentialKeys.isNotEmpty()) {
                appendLine("## Available test credentials & the `resolve` step")
                appendLine("These credential KEYS are available to the recorder as environment variables (the")
                appendLine("VALUES are injected at run time and are NOT shown here):")
                credentialKeys.forEach { appendLine("- $it") }
                appendLine()
                appendLine("When a browser `type` step must enter a value that has to MATCH one of these credentials")
                appendLine("— above all the login/registration email, which must be the SAME inbox your `resolve`")
                appendLine("step reads the code from — write it as \${KEY}, e.g.:")
                appendLine("    - action: type")
                appendLine("      selector: \"[data-testid=\\\"email-input\\\"]\"")
                appendLine("      value: \${TEST_EMAIL_INBOX}")
                appendLine("The recorder substitutes the real injected value at run time. Do NOT invent a placeholder")
                appendLine("email like test@example.com — the code is sent to whatever you type, so a made-up address")
                appendLine("never receives it and the resolve step then finds no code.")
                appendLine()
                appendLine("If completing the feature end-to-end needs a value obtainable only out-of-band (e.g. a")
                appendLine("login code emailed to a test inbox, an SMS/OTP, or an API value), emit a `resolve` step.")
                appendLine("It runs ONE single-line command inside the recorder container with those env vars set,")
                appendLine("and binds the command's stdout (trimmed) to a variable you reference as \${name} in a")
                appendLine("later step's value. YOU choose how to fetch it (IMAP via `python3 -c \"...\"`, an HTTP")
                appendLine("API, etc.); nothing is hardcoded. Example:")
                appendLine("    - action: resolve")
                appendLine("      name: code")
                appendLine("      run: python3 -c \"import imaplib,os; ...; print(code)\"")
                appendLine("    - action: type")
                appendLine("      selector: \"[data-testid=\\\"code-input\\\"]\"")
                appendLine("      value: \${code}")
                appendLine()
                appendLine("CRITICAL rules for a `resolve` command so it actually returns a value:")
                appendLine("- Read EVERY connection parameter from the env vars listed above via os.getenv/os.environ")
                appendLine("  — host, port, USERNAME, and password. NEVER hardcode a username, host, port, or")
                appendLine("  sender. For IMAP login use the exact user key provided (e.g. os.environ['TEST_EMAIL_IMAP_USER']),")
                appendLine("  which is the full login the provider expects — do not shorten an email to its local part.")
                appendLine("- Do NOT filter by a guessed sender/subject. Fetch the most RECENT message(s) in the inbox")
                appendLine("  and extract the code by shape (e.g. the first 4-8 digit run: re.search(r'\\\\b(\\\\d{4,8})\\\\b', body)).")
                appendLine("- Poll briefly (a few retries with a short sleep) since the email may take seconds to arrive.")
                appendLine("- The command MUST exit 0 and print ONLY the resolved value (nothing else). If it errors or")
                appendLine("  prints a placeholder, \${name} is left unsubstituted and the literal text is typed instead.")
                appendLine()
            }
            append("Generate the demo_scenario YAML now. The scenario must include scrolling and button pressing, with at least one scroll action and at least one button click.")
        }
    }

    internal fun extractRealSelectors(diff: String): List<String> {
        val attrPattern = Regex("""(data-testid|data-[a-z-]+|id|name|aria-label)=["']([^"']+)["']""")
        val selectors = linkedSetOf<String>()
        for (line in diff.lineSequence()) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue
            for (match in attrPattern.findAll(line)) {
                val (attr, value) = match.destructured
                if (value.isBlank()) continue
                selectors.add("[$attr=\"$value\"]")
            }
        }
        return selectors.toList()
    }

    internal fun extractRealRoutes(diff: String): List<String> {
        // Matches route registrations across the common backend frameworks (FastAPI/Flask
        // decorators, Express/Koa-style app.get/router.get calls). Only added lines count —
        // this is meant to find NEW routes this PR introduces, not pre-existing ones.
        val routePattern = Regex(
            """(?:@\w+\.(?:get|post|put|delete|patch|route)|(?:app|router)\.(?:get|post|put|delete|patch|use))\s*\(\s*["']([^"']+)["']"""
        )
        val routes = linkedSetOf<String>()
        for (line in diff.lineSequence()) {
            if (!line.startsWith("+") || line.startsWith("+++")) continue
            for (match in routePattern.findAll(line)) {
                val path = match.groupValues[1]
                if (path.startsWith("/") && path != "/") routes.add(path)
            }
        }
        return routes.toList()
    }

    internal fun ensureNavigatesToRealRoute(block: String, routes: List<String>): String {
        if (routes.isEmpty()) return block
        // If the model already navigates somewhere other than the root, trust it — it may have
        // picked a different (also valid) route, or reached the target page via an in-page
        // click the recorder will actually perform.
        val hasNonRootNavigate = Regex("""action:\s*navigate[\s\S]*?url:\s*["']?([^"'\n]+)""")
            .findAll(block)
            .any { it.groupValues[1].trim().trim('"', '\'').let { u -> u.isNotBlank() && u != "/" } }
        if (hasNonRootNavigate) return block

        val stepsMatch = Regex("""steps:\s*\n""").find(block) ?: return block
        val stepIndent = Regex("""\n(\s*)- action:""").find(block)?.groupValues?.get(1) ?: "    "
        val injected = "$stepIndent- action: navigate\n" +
            "$stepIndent  url: \"${routes.first()}\"\n" +
            "$stepIndent  waitUntil: networkidle\n"
        val insertAt = stepsMatch.range.last + 1
        return block.substring(0, insertAt) + injected + block.substring(insertAt)
    }

    private fun runGitDiff(workDir: File): String? {
        // Deployed target workspaces are typically a clone checked out on the PR branch with only
        // a remote-tracking `origin/main` — no local `main` ref. `git diff main...HEAD` there dies
        // with "fatal: ambiguous argument 'main...HEAD'", so grounding silently no-ops and the
        // model hallucinates selectors. Try `main` first (the common local-dev/test case), then
        // fall back to `origin/main` before giving up.
        for (baseRef in DIFF_BASE_REFS) {
            gitDiffAgainst(workDir, baseRef)?.let { return it }
        }
        return null
    }

    private fun gitDiffAgainst(workDir: File, baseRef: String): String? = try {
        val pb = ProcessBuilder("git", "diff", "$baseRef...HEAD")
            .directory(workDir)
            .redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        // redirectErrorStream(true) merges stderr into stdout, so a failure (e.g. this base ref
        // doesn't resolve in this checkout) produces non-blank "fatal: ..." text that isBlank()
        // alone wouldn't catch — that text would silently pass for a real diff, and
        // extractRealSelectors/extractRealRoutes would then find nothing in it. Require a
        // clean exit before trusting the output at all.
        if (!completed || process.exitValue() != 0) {
            logger.warn("demo_scenario_git_diff_failed", mapOf("base_ref" to baseRef, "error" to output.take(200)))
            null
        } else {
            output.takeIf { it.isNotBlank() }
        }
    } catch (e: Exception) {
        logger.warn("demo_scenario_git_diff_failed", mapOf("base_ref" to baseRef, "error" to (e.message ?: "unknown")))
        null
    }

    /**
     * Pulls the scenario YAML out of a model response.
     *
     * [rootKey] selects which kind of scenario to look for — `demo_scenario` for the browser
     * recorder, `api_scenario` for the server-side one. The three tolerated shapes are identical
     * for both, so the API path reuses this rather than growing a second, differently-buggy parser.
     */
    internal fun extractScenarioBlock(raw: String, rootKey: String = "demo_scenario"): String? {
        // Format A: ```yaml <rootKey>\n<content without the <rootKey>: wrapper>\n``` —
        // the label is on the fence line itself, so the content needs wrapping/reindenting.
        val labeledFenceMatch = Regex(
            """```(?:yaml|yml)\s+${Regex.escape(rootKey)}\s*\n(.*?)\n```""",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(raw).lastOrNull()
        if (labeledFenceMatch != null) {
            val yamlContent = labeledFenceMatch.groupValues[1].trim()
            if (yamlContent.isBlank()) return null
            return "$rootKey:\n" + yamlContent.lines().joinToString("\n") { line ->
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
            .filter { it.trimStart().startsWith("$rootKey:") }
            .lastOrNull()
        if (plainFenceMatch != null) {
            return plainFenceMatch.trim().ifBlank { null }
        }

        // Format C: unfenced "<rootKey>:\n  ..." — tolerate blank lines *within* the block
        // (models routinely add them for readability) without swallowing unrelated trailing
        // prose once genuine non-indented, non-blank content appears.
        val rawMatch = Regex("""(?m)^${Regex.escape(rootKey)}:\s*\n(?:(?:[ \t]+.*)?\n)*""")
            .findAll(raw)
            .lastOrNull { it.value.trim() != "$rootKey:" }
        if (rawMatch != null) {
            return rawMatch.value.trimEnd()
        }
        return null
    }

    companion object {
        /** Conventional staging dir the recorder resolves scenario + credentials from, keyed by issue. */
        const val SCENARIO_DIR = "/tmp/koncerto-demo"

        // Must match DemoScenarioFiles in koncerto-demo, which is the reader side of this
        // convention. Duplicated rather than shared because the orchestrator deliberately does not
        // depend on koncerto-demo; the suffixes are pinned by tests on both sides.
        const val BROWSER_SCENARIO_SUFFIX = "-scenario.yaml"
        const val API_SCENARIO_SUFFIX = "-api-scenario.yaml"
        private const val API_ROOT_KEY = "api_scenario"
        // Mirrors the 8000-char diff cap: a service with hundreds of routes would otherwise
        // push the prompt past every free model's context and fail generation outright.
        private const val MAX_INVENTORY_CHARS = 8000

        /** Base refs tried in order for the grounding diff: local `main` first, then the
         *  remote-tracking `origin/main` that PR-branch clones actually have. */
        private val DIFF_BASE_REFS = listOf("main", "origin/main")

        // Measured 8-139s for successful runs against the real prompt; hangs (now much rarer
        // after fixing the stdin/permission/deadlock issues) still occasionally ride out the
        // full timeout. 300s per attempt plus several passes trades worst-case wall time for a
        // much higher chance of eventually landing a real scenario, which is now mandatory.
        private const val PER_ATTEMPT_TIMEOUT_SECONDS = 300L
        private const val PASS_COUNT = 3

        fun defaultProcessRunner(): ProcessRunner = ProcessRunner { command, workDir, timeoutSeconds ->
            try {
                // Without this, the child's stdin is an open, never-closed pipe by default. A CLI
                // that probes stdin for piped input when it isn't attached to a real terminal can
                // block waiting for an EOF that never arrives; /dev/null delivers one immediately.
                val pb = ProcessBuilder(command).directory(workDir).redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
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
