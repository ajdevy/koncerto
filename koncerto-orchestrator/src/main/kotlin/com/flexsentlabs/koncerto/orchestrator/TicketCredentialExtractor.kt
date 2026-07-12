package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

/**
 * Extracts test credentials / keys that an operator wrote into a ticket's FREE TEXT (title +
 * description) so the demo can use them — e.g. a test email inbox address, an app password, an IMAP
 * host, or an API key mentioned in prose. There is no special section or fenced block: the model
 * reads the whole text and returns the KEY=VALUE pairs it finds.
 *
 * These are merged UNDER the project's demo secrets file (the file always wins on a key collision).
 * LLM-driven and deliberately fail-open: any error, timeout, or unparseable reply yields an empty
 * map, so a detection failure never blocks or corrupts the demo run — the secrets file still applies.
 */
class TicketCredentialExtractor(
    // Uses Claude Code (`claude -p …`), not the free opencode models: extraction is a tiny but
    // critical-path task, and the free models proved intermittently unavailable, silently returning
    // an empty map and breaking the demo. Haiku 4.5 does this trivial extraction reliably and is the
    // cheapest/fastest Claude model; Sonnet is the fallback. No extended thinking — nothing to reason
    // about, just find the values and emit JSON.
    private val command: String = "claude",
    private val logger: StructuredLogger,
    private val processRunner: DemoScenarioGenerator.ProcessRunner = DemoScenarioGenerator.defaultProcessRunner(),
    private val models: List<String> = listOf("haiku", "sonnet")
) {
    fun extract(issue: Issue): Map<String, String> {
        val prompt = buildPrompt(issue.title, issue.description.orEmpty())
        for (model in models) {
            val cmd = command.split(" ") + listOf("-p", prompt, "--model", model, "--output-format", "text")
            val output = try {
                processRunner.run(cmd, WORK_DIR, TIMEOUT_SECONDS)
            } catch (e: Exception) {
                logger.warn("ticket_credential_extract_error", mapOf(
                    "issue_id" to issue.id, "model" to model, "error" to (e.message ?: "unknown")))
                null
            } ?: continue
            parse(output)?.let { return it }
        }
        return emptyMap()
    }

    private fun buildPrompt(title: String, description: String): String = """
        You extract TEST credentials/keys an operator wrote into a ticket so an automated demo can use
        them. Read the whole ticket text (no special section) and return every concrete credential,
        account, host, or key mentioned — for example an email address, an app/IMAP/SMTP password, an
        IMAP host and port, an API key or token. Give each a stable UPPER_SNAKE_CASE key a program
        would use as an env var (e.g. TEST_EMAIL_INBOX, TEST_EMAIL_IMAP_PASSWORD, TEST_EMAIL_IMAP_HOST,
        TEST_EMAIL_IMAP_PORT, TEST_EMAIL_IMAP_USER, <SERVICE>_API_KEY). Only include values actually
        present in the text; never invent or guess a value. If none are present, return an empty array.

        Reply with ONLY a JSON array (no prose) of objects {"key": <UPPER_SNAKE_CASE>, "value": <literal value>}.
        Example: [{"key":"TEST_EMAIL_INBOX","value":"qa@example.com"},{"key":"TEST_EMAIL_IMAP_HOST","value":"imap.example.com"}]
        Nothing present: []

        TICKET TITLE: $title
        TICKET DESCRIPTION:
        $description
    """.trimIndent()

    private fun parse(output: String): Map<String, String>? {
        val json = TestResourceRequirementDetector.extractJsonArray(output) ?: return null
        return try {
            val map = LinkedHashMap<String, String>()
            JSON.parseToJsonElement(json).jsonArray.forEach { el ->
                val obj = el as? JsonObject ?: return@forEach
                val key = (obj["key"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                val value = (obj["value"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@forEach
                map[key] = value
            }
            map
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private val WORK_DIR = File(System.getProperty("java.io.tmpdir"))
        private val JSON = Json { ignoreUnknownKeys = true }
    }
}
