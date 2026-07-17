package com.flexsentlabs.koncerto.core.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Parses the output of one review-runtime invocation into a [ReviewParseResult].
 *
 * Two layers, both defensive (architecture-review-quality.md §4.1, D-1/D-2):
 *  1. CLI envelope: when the command runs with `--output-format json`, stdout is a JSON
 *     object carrying `result` (the model text), `usage`, `duration_ms`, `is_error`.
 *     When it is plain streamed text, the whole input is treated as the model text.
 *  2. Findings: a fenced ```review-findings block inside the model text, decoded as
 *     [ReviewFindingsPayload]. Any failure falls back to legacy verdict-string parsing
 *     with [ParseStatus.FALLBACK] so the pipeline never stalls (NFR-02).
 */
object ReviewOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val FENCE_REGEX = Regex(
        """```(?:review-findings|json:review-findings)\s*\n(.*?)\n```""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    // Legacy verdict markers (prompts/review.md v1) used only on the fallback path.
    private val FAIL_MARKERS = listOf("❌ FAIL", "❌ **Changes requested**", "❌ Changes requested")

    fun parse(rawStdout: String, promptVersion: String? = null): ReviewParseResult {
        val envelope = extractEnvelope(rawStdout)
        val modelText = envelope.resultText
        val usage = envelope.usage
        val humanText = stripFindingsBlock(modelText)

        val findings = extractFindings(modelText)
        if (findings != null) {
            return ReviewParseResult(
                verdictPass = findings.none { it.severity == Severity.CRITICAL },
                findings = findings,
                usage = usage,
                promptVersion = promptVersion,
                parseStatusName = ParseStatus.OK.name,
                humanText = humanText
            )
        }

        // Fallback: no parseable findings block — derive verdict from legacy markers.
        return ReviewParseResult(
            verdictPass = !containsFailMarker(modelText),
            findings = emptyList(),
            usage = usage,
            promptVersion = promptVersion,
            parseStatusName = ParseStatus.FALLBACK.name,
            humanText = humanText
        )
    }

    /** Removes the machine-only findings block so PR comments stay human-readable. */
    private fun stripFindingsBlock(modelText: String): String =
        FENCE_REGEX.replace(modelText, "").trim()

    private data class Envelope(val resultText: String, val usage: ReviewUsage)

    private fun extractEnvelope(raw: String): Envelope {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) return Envelope(raw, ReviewUsage.EMPTY)
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull()
            ?: return Envelope(raw, ReviewUsage.EMPTY)

        val resultText = (obj["result"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["text"] as? JsonPrimitive)?.contentOrNull
            ?: raw
        val usageObj = obj["usage"] as? JsonObject
        val input = usageObj?.get("input_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val output = usageObj?.get("output_tokens")?.jsonPrimitive?.longOrNull ?: 0
        val durationMs = obj["duration_ms"]?.jsonPrimitive?.longOrNull
            ?: obj["duration"]?.jsonPrimitive?.longOrNull ?: 0
        val isError = (obj["is_error"] as? JsonPrimitive)?.contentOrNull?.toBoolean() ?: false

        return Envelope(
            resultText,
            ReviewUsage(
                inputTokens = input,
                outputTokens = output,
                totalTokens = input + output,
                durationMs = durationMs,
                isError = isError
            )
        )
    }

    /** Returns null when there is no parseable findings block (caller falls back). */
    private fun extractFindings(modelText: String): List<ReviewFinding>? {
        val block = FENCE_REGEX.find(modelText)?.groupValues?.get(1)?.trim() ?: return null
        val payload = runCatching { json.decodeFromString<ReviewFindingsPayload>(block) }.getOrNull()
            ?: return null
        return payload.findings
    }

    private fun containsFailMarker(text: String): Boolean =
        FAIL_MARKERS.any { text.contains(it) }
}
