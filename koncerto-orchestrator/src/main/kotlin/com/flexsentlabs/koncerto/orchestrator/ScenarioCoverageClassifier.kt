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

/** A positive scenario the ticket promises that a browser recorder cannot exercise end-to-end. */
data class UnverifiableScenario(val scenario: String, val why: String = "")

/**
 * Classifies which of a ticket's positive scenarios an automated browser recorder cannot exercise
 * end-to-end — i.e. scenarios that require an out-of-band step the recorder can't perform (reading an
 * emailed login code from an inbox, an SMS OTP, an external OAuth hand-off). The demo pipeline uses
 * this to annotate the PR comment with a coverage note, so a recording that only reached an
 * intermediate screen is not mistaken for a fully-verified positive scenario.
 *
 * LLM-driven and fail-open: any error, timeout, or unparseable reply yields an empty list (no note).
 */
class ScenarioCoverageClassifier(
    private val command: String,
    private val logger: StructuredLogger,
    private val processRunner: DemoScenarioGenerator.ProcessRunner = DemoScenarioGenerator.defaultProcessRunner(),
    private val models: List<String> = FreeModelCycler.DEFAULT_FREE_MODELS
) {
    fun classify(issue: Issue): List<UnverifiableScenario> {
        val prompt = buildPrompt(issue.title, issue.description.orEmpty())
        for (model in models) {
            val cmd = command.split(" ") + listOf("run", "--model", model, "--dangerously-skip-permissions", prompt)
            val output = try {
                processRunner.run(cmd, WORK_DIR, TIMEOUT_SECONDS)
            } catch (e: Exception) {
                logger.warn("scenario_coverage_error", mapOf(
                    "issue_id" to issue.id, "model" to model, "error" to (e.message ?: "unknown")))
                null
            } ?: continue
            parse(output)?.let { return it }
        }
        return emptyList()
    }

    private fun buildPrompt(title: String, description: String): String = """
        You are a demo-coverage assistant for an autonomous pipeline that records a headless browser
        walking through a feature. Given a ticket, list the POSITIVE scenarios it promises that such a
        browser recording CANNOT exercise end-to-end because they require an out-of-band step the
        recorder cannot perform — for example reading an emailed login code from a real inbox, entering
        an SMS one-time code, or completing an external OAuth/redirect the recorder can't drive. Only
        list scenarios that are genuinely un-automatable this way; if every positive scenario is fully
        exercisable in the browser, return an empty array.

        Reply with ONLY a JSON array (no prose) of objects {"scenario": <short phrase>, "why": <one line>}.
        Example: [{"scenario":"log in via emailed code","why":"requires reading the emailed code from an inbox the recorder can't access"}]
        All scenarios automatable: []

        TICKET TITLE: $title
        TICKET DESCRIPTION:
        $description
    """.trimIndent()

    private fun parse(output: String): List<UnverifiableScenario>? {
        val json = TestResourceRequirementDetector.extractJsonArray(output) ?: return null
        return try {
            JSON.parseToJsonElement(json).jsonArray.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val scenario = (obj["scenario"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                UnverifiableScenario(scenario, (obj["why"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty())
            }
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
