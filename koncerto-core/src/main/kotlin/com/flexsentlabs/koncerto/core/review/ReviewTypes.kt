package com.flexsentlabs.koncerto.core.review

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shared review-quality domain types (Epics 18-23).
 *
 * Pure data + enums with no IO. Everything that flows between the review runtime,
 * the orchestrator pipeline stages, and the metrics store is defined here so the
 * agent module never has to depend on metrics (see architecture-review-quality.md D-8).
 */

@Serializable
enum class Severity {
    @SerialName("critical") CRITICAL,
    @SerialName("warning") WARNING,
    @SerialName("suggestion") SUGGESTION;

    companion object {
        fun fromWire(value: String?): Severity? = when (value?.trim()?.lowercase()) {
            "critical", "blocker", "blocking" -> CRITICAL
            "warning", "warn", "major", "minor" -> WARNING
            "suggestion", "nit", "info", "style" -> SUGGESTION
            else -> null
        }
    }
}

@Serializable
enum class RiskTier {
    @SerialName("low") LOW,
    @SerialName("standard") STANDARD,
    @SerialName("critical") CRITICAL;

    companion object {
        fun fromWire(value: String?): RiskTier = when (value?.trim()?.lowercase()) {
            "low" -> LOW
            "critical" -> CRITICAL
            else -> STANDARD
        }
    }
}

@Serializable
enum class ReviewMode {
    @SerialName("advisory") ADVISORY,
    @SerialName("blocking") BLOCKING;

    companion object {
        fun fromWire(value: String?): ReviewMode = when (value?.trim()?.lowercase()) {
            "advisory" -> ADVISORY
            else -> BLOCKING
        }
    }
}

/**
 * Terminal disposition of a published finding, used to compute the "high evidence
 * outcome" rate (fixed / likely_fixed / discussed count as high-evidence).
 */
@Serializable
enum class FindingOutcome {
    @SerialName("fixed") FIXED,
    @SerialName("likely_fixed") LIKELY_FIXED,
    @SerialName("wont_fix") WONT_FIX,
    @SerialName("not_a_bug") NOT_A_BUG,
    @SerialName("discussed") DISCUSSED,
    @SerialName("ignored") IGNORED;

    /** High-evidence outcomes: finding led to a code change or explicit discussion. */
    fun isHighEvidence(): Boolean = this == FIXED || this == LIKELY_FIXED || this == DISCUSSED

    companion object {
        fun fromWire(value: String?): FindingOutcome? = when (value?.trim()?.lowercase()) {
            "fixed" -> FIXED
            "likely_fixed" -> LIKELY_FIXED
            "wont_fix", "wontfix" -> WONT_FIX
            "not_a_bug", "notabug", "false_positive" -> NOT_A_BUG
            "discussed" -> DISCUSSED
            "ignored" -> IGNORED
            else -> null
        }
    }
}

@Serializable
enum class HumanLabel {
    @SerialName("accept") ACCEPT,
    @SerialName("reject") REJECT,
    @SerialName("false_positive") FALSE_POSITIVE;

    companion object {
        fun fromWire(value: String?): HumanLabel? = when (value?.trim()?.lowercase()) {
            "accept", "accepted", "up", "thumbs_up" -> ACCEPT
            "reject", "rejected", "down", "thumbs_down" -> REJECT
            "false_positive", "fp", "falsepositive" -> FALSE_POSITIVE
            else -> null
        }
    }
}

/**
 * A single review finding as emitted by the model in the fenced `review-findings` block.
 * `seq` is the model-assigned ordinal within one review response; the durable identity is
 * [findingId] = "{runId}-{seq}", assigned by the orchestrator when persisting.
 */
@Serializable
data class ReviewFinding(
    val seq: Int,
    val category: String,
    val severity: Severity,
    val confidence: Double? = null,
    val file: String? = null,
    val line: Int? = null,
    val description: String,
    val expectedAction: String? = null,
    val evidence: String? = null,
    /** Non-null only for multi-agent critical-tier reviews (Epic 23). */
    val specialist: String? = null
)

/** The model-emitted envelope decoded from the fenced `review-findings` JSON block. */
@Serializable
data class ReviewFindingsPayload(
    val findings: List<ReviewFinding> = emptyList()
)

/**
 * One entry of the fix agent's `.review-fix-report.json` handoff (Epic 22, D-4): what the
 * agent decided to do about a specific published finding.
 */
@Serializable
data class FixDisposition(
    val findingId: String,
    val disposition: String,   // fixed | wont_fix | not_a_bug
    val note: String? = null
)

/** Token/latency accounting extracted from `claude --print --output-format json`. */
@Serializable
data class ReviewUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val totalTokens: Long = 0,
    val durationMs: Long = 0,
    val isError: Boolean = false
) {
    companion object {
        val EMPTY = ReviewUsage()
    }
}

enum class ParseStatus { OK, FALLBACK }

/**
 * Result of parsing one review runtime invocation. Written to `.review-findings.json`
 * for the orchestrator to persist. On any structured-parse failure we degrade to the
 * legacy verdict-string path and record [parseStatus] = FALLBACK (NFR-02).
 */
@Serializable
data class ReviewParseResult(
    val verdictPass: Boolean,
    val findings: List<ReviewFinding> = emptyList(),
    val usage: ReviewUsage = ReviewUsage.EMPTY,
    val promptVersion: String? = null,
    val parseStatusName: String = ParseStatus.OK.name,
    /**
     * The human-readable review text with the machine-only findings block stripped — this is
     * what gets written to `.review-output` and published as the PR comment. Unwrapped from
     * the CLI's JSON envelope when one is present.
     */
    val humanText: String = ""
) {
    val parseStatus: ParseStatus
        get() = if (parseStatusName == ParseStatus.FALLBACK.name) ParseStatus.FALLBACK else ParseStatus.OK
}
