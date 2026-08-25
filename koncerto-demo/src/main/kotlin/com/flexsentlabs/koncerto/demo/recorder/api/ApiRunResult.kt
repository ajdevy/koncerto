package com.flexsentlabs.koncerto.demo.recorder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One request the demo script actually issued, as recorded by the script itself. */
@Serializable
data class ApiStepOutcome(
    val step: Int = 0,
    val method: String = "",
    val path: String = "",
    val code: String = "",
    val expected: String = "",
    val ok: Boolean = false,
    val error: String = ""
)

/**
 * The verdict for one API demo run, read back from the JSONL the recording script appends to.
 *
 * A recording that produced a video but failed an assertion is still a failure: the pipeline's
 * repair/escalation loop must see it, so a video of a broken endpoint is never published as though
 * the ticket were verified.
 */
data class ApiRunResult(
    val outcomes: List<ApiStepOutcome>,
    val expectedRequests: Int
) {
    val executed: Int get() = outcomes.size
    val failed: List<ApiStepOutcome> get() = outcomes.filterNot { it.ok }

    /** A precise, human-readable reason the run failed, or null when everything passed. */
    fun failureReason(): String? = when {
        outcomes.isEmpty() ->
            "the demo script issued no requests — the scenario produced no executable step"
        executed < expectedRequests ->
            "the demo stopped after $executed of $expectedRequests requests" +
                (failed.firstOrNull()?.let { "; last failure: ${describe(it)}" } ?: "")
        failed.isNotEmpty() ->
            "${failed.size} of $executed requests failed: " +
                failed.joinToString("; ") { describe(it) }
        else -> null
    }

    private fun describe(outcome: ApiStepOutcome): String =
        "${outcome.method} ${outcome.path} -> ${outcome.code}" +
            outcome.error.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Parses the script's JSONL output, skipping any line that isn't a well-formed record. */
        fun parse(jsonl: String, expectedRequests: Int): ApiRunResult = ApiRunResult(
            outcomes = jsonl.lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { JSON.decodeFromString<ApiStepOutcome>(it) }.getOrNull() }
                .toList(),
            expectedRequests = expectedRequests
        )
    }
}
