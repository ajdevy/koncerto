package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.FreeModelCycler
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import java.io.File

/** An external test resource a feature needs to be built and verified end-to-end. */
data class RequiredResource(val name: String, val why: String = "")

/**
 * Infers, from a ticket's title + description, the concrete EXTERNAL test resources a feature needs
 * to be built and verified end-to-end that cannot be created from the repository alone — e.g. a real
 * email inbox to receive a login code, a payment-provider sandbox account, a third-party test login.
 * Used by the pre-implementation preflight to block a ticket (with a comment) before any agent runs
 * when a required resource is missing.
 *
 * LLM-driven and deliberately fail-open: any error, timeout, or unparseable reply yields an empty
 * list (the gate then passes). A false gate that blocks legitimate work is worse than a missed one.
 */
class TestResourceRequirementDetector(
    private val command: String,
    private val logger: StructuredLogger,
    private val processRunner: DemoScenarioGenerator.ProcessRunner = DemoScenarioGenerator.defaultProcessRunner(),
    private val models: List<String> = FreeModelCycler.DEFAULT_FREE_MODELS
) {
    fun detect(issue: Issue): List<RequiredResource> {
        val prompt = buildPrompt(issue.title, issue.description.orEmpty())
        for (model in models) {
            val cmd = command.split(" ") + listOf("run", "--model", model, "--dangerously-skip-permissions", prompt)
            val output = try {
                processRunner.run(cmd, WORK_DIR, TIMEOUT_SECONDS)
            } catch (e: Exception) {
                logger.warn("test_resource_detect_error", mapOf(
                    "issue_id" to issue.id, "model" to model, "error" to (e.message ?: "unknown")))
                null
            } ?: continue
            parse(output)?.let { return it }
        }
        return emptyList()
    }

    private fun buildPrompt(title: String, description: String): String = """
        You are a release-gating assistant for an autonomous coding pipeline. Given a ticket, list the
        CONCRETE EXTERNAL TEST RESOURCES a developer must be handed to build AND verify this feature
        end-to-end that cannot be created from the repository alone — for example a real email inbox to
        receive an emailed login code, a payment-provider sandbox account, or a third-party test login.
        Do NOT list ordinary environment variables or API keys the repo already declares, and do NOT
        list anything derivable within the repository. Be conservative: if nothing external is truly
        required, return an empty array.

        Reply with ONLY a JSON array (no prose) of objects {"name": <short stable noun phrase>, "why": <one line>}.
        Example: [{"name":"test email inbox","why":"receive and read the emailed login code end-to-end"}]
        Nothing external required: []

        TICKET TITLE: $title
        TICKET DESCRIPTION:
        $description
    """.trimIndent()

    private fun parse(output: String): List<RequiredResource>? {
        val json = extractJsonArray(output) ?: return null
        return try {
            JSON.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                RequiredResource(name, (obj["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 120L
        private val WORK_DIR = File(System.getProperty("java.io.tmpdir"))
        private val JSON = Json { ignoreUnknownKeys = true }

        /** First balanced top-level JSON array in [text], tolerating prose/markdown around it. */
        internal fun extractJsonArray(text: String): String? {
            val start = text.indexOf('[')
            if (start < 0) return null
            var depth = 0
            for (i in start until text.length) {
                when (text[i]) {
                    '[' -> depth++
                    ']' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            return null
        }
    }
}
