package com.flexsentlabs.koncerto.orchestrator.review

import com.flexsentlabs.koncerto.core.review.FindingMerge
import com.flexsentlabs.koncerto.core.review.FixDisposition
import com.flexsentlabs.koncerto.core.review.ReviewFinding
import com.flexsentlabs.koncerto.core.review.Severity
import com.flexsentlabs.koncerto.metrics.ReviewFindingRecord
import com.flexsentlabs.koncerto.metrics.ReviewMetricsRepository
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tracks the outcome of published findings (Epic 22), in precedence order:
 *  1. Fix-agent self-report (`.review-fix-report.json`) — precise but not always complete.
 *  2. Re-review corroboration — a prior finding absent from the fresh review is `likely_fixed`.
 * Human labels (set via the dashboard endpoint) override both and are not touched here.
 */
class FindingOutcomeTracker(private val metrics: ReviewMetricsRepository?) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Ingests the fix agent's per-finding dispositions. Returns count applied. */
    suspend fun applyFixReport(workspacePath: Path): Int {
        val p = workspacePath.resolve(".review-fix-report.json")
        if (!Files.exists(p)) return 0
        val text = runCatching { Files.readString(p) }.getOrNull() ?: return 0
        val entries = runCatching {
            json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(FixDisposition.serializer()), text)
        }.getOrNull() ?: return 0
        var applied = 0
        for (e in entries) {
            val outcome = normalizeDisposition(e.disposition) ?: continue
            metrics?.updateOutcome(e.findingId, outcome, "fix_agent")
            applied++
        }
        return applied
    }

    /**
     * Marks prior published findings that no longer appear in [currentFindings] as
     * `likely_fixed`. Only findings without an existing outcome are touched.
     */
    suspend fun applyRereview(issueId: String, currentFindings: List<ReviewFinding>): Int {
        val prior = metrics?.publishedFindingsForIssue(issueId) ?: return 0
        var updated = 0
        for (record in prior) {
            if (record.outcome != null) continue
            val priorAsFinding = record.toCoreFinding()
            val stillPresent = currentFindings.any { FindingMerge.sameDefect(priorAsFinding, it) }
            if (!stillPresent) {
                metrics?.updateOutcome(record.findingId, "likely_fixed", "rereview")
                updated++
            }
        }
        return updated
    }

    private fun normalizeDisposition(raw: String): String? = when (raw.trim().lowercase()) {
        "fixed", "resolved", "done" -> "fixed"
        "wont_fix", "wontfix", "skip", "skipped" -> "wont_fix"
        "not_a_bug", "notabug", "false_positive", "invalid" -> "not_a_bug"
        "discussed" -> "discussed"
        else -> null
    }

    private fun ReviewFindingRecord.toCoreFinding() = ReviewFinding(
        seq = seq,
        category = category,
        severity = Severity.fromWire(severity) ?: Severity.WARNING,
        confidence = confidence,
        file = file,
        line = line,
        description = description,
        expectedAction = expectedAction,
        evidence = evidence,
        specialist = specialist
    )
}
