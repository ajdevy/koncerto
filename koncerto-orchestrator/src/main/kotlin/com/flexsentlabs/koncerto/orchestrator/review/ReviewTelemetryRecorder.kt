package com.flexsentlabs.koncerto.orchestrator.review

import com.flexsentlabs.koncerto.core.review.GateResult
import com.flexsentlabs.koncerto.core.review.PublicationGate
import com.flexsentlabs.koncerto.core.review.ReviewMode
import com.flexsentlabs.koncerto.core.review.ReviewParseResult
import com.flexsentlabs.koncerto.core.review.ReviewPolicy
import com.flexsentlabs.koncerto.core.review.RiskTier
import com.flexsentlabs.koncerto.metrics.ReviewFindingRecord
import com.flexsentlabs.koncerto.metrics.ReviewMetricsRepository
import com.flexsentlabs.koncerto.metrics.ReviewRunRecord
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** Metadata needed to persist a review run alongside its parsed findings. */
data class ReviewRunContext(
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String?,
    val attempt: Int,
    val commitSha: String?,
    val prNumber: Int?,
    val model: String?,
    val riskTier: RiskTier,
    val reviewMode: ReviewMode,
    val contextComposition: Map<String, Int> = emptyMap()
)

/** What a recorded review run produced: its durable id and the gate's publish/drop split. */
data class RecordedReview(val runId: String, val gate: GateResult)

/**
 * Applies the publication gate to parsed findings, persists the run + findings to the
 * metrics store (Epic 18/21), and returns the gate result so the caller can render the PR
 * comment from published findings only. Best-effort: a metrics failure never breaks review.
 */
class ReviewTelemetryRecorder(
    private val metrics: ReviewMetricsRepository?,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Instant = { Instant.now() }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Records a run that was skipped by the eligibility pre-check (no model call). */
    suspend fun recordSkipped(ctx: ReviewRunContext, eligibilityReason: String): String {
        val runId = idGen()
        metrics?.recordRun(
            ReviewRunRecord(
                runId = runId,
                issueId = ctx.issueId,
                issueIdentifier = ctx.issueIdentifier,
                projectSlug = ctx.projectSlug,
                attempt = ctx.attempt,
                commitSha = ctx.commitSha,
                prNumber = ctx.prNumber,
                model = ctx.model,
                promptVersion = null,
                riskTier = ctx.riskTier.name.lowercase(),
                reviewMode = ctx.reviewMode.name.lowercase(),
                eligibility = eligibilityReason,
                parseStatus = null,
                verdict = "skipped",
                findingsTotal = 0,
                findingsPublished = 0,
                inputTokens = 0,
                outputTokens = 0,
                durationMs = 0,
                contextPackJson = null,
                createdAt = clock().toString()
            )
        )
        return runId
    }

    /** Records a completed review run + its gated findings. */
    suspend fun record(ctx: ReviewRunContext, parsed: ReviewParseResult, policy: ReviewPolicy): RecordedReview {
        val runId = idGen()
        val gate = PublicationGate.apply(parsed.findings, policy)
        val now = clock().toString()

        val findingRecords = gate.gated.map { g ->
            val f = g.finding
            ReviewFindingRecord(
                findingId = "$runId-${f.seq}",
                runId = runId,
                seq = f.seq,
                specialist = f.specialist,
                category = f.category,
                severity = f.severity.name.lowercase(),
                confidence = f.confidence,
                file = f.file,
                line = f.line,
                description = f.description,
                expectedAction = f.expectedAction,
                evidence = f.evidence,
                published = g.published,
                dropReason = g.dropReason,
                outcome = null,
                outcomeSource = null,
                humanLabel = null,
                issueId = ctx.issueId,
                updatedAt = now
            )
        }

        metrics?.recordRun(
            ReviewRunRecord(
                runId = runId,
                issueId = ctx.issueId,
                issueIdentifier = ctx.issueIdentifier,
                projectSlug = ctx.projectSlug,
                attempt = ctx.attempt,
                commitSha = ctx.commitSha,
                prNumber = ctx.prNumber,
                model = ctx.model,
                promptVersion = parsed.promptVersion,
                riskTier = ctx.riskTier.name.lowercase(),
                reviewMode = ctx.reviewMode.name.lowercase(),
                eligibility = "reviewed",
                parseStatus = parsed.parseStatus.name.lowercase(),
                verdict = if (parsed.verdictPass) "pass" else "fail",
                findingsTotal = parsed.findings.size,
                findingsPublished = gate.published.size,
                inputTokens = parsed.usage.inputTokens,
                outputTokens = parsed.usage.outputTokens,
                durationMs = parsed.usage.durationMs,
                contextPackJson = runCatching { json.encodeToString(mapEntrySerializer, ctx.contextComposition) }
                    .getOrNull(),
                createdAt = now
            )
        )
        metrics?.recordFindings(findingRecords)
        return RecordedReview(runId, gate)
    }

    companion object {
        private val mapEntrySerializer = MapSerializer(String.serializer(), Int.serializer())

        /** Reads the runtime's `.review-findings.json` handoff; null if absent/unparseable. */
        fun readParseResult(workspacePath: Path): ReviewParseResult? {
            val p = workspacePath.resolve(".review-findings.json")
            if (!Files.exists(p)) return null
            val text = runCatching { Files.readString(p) }.getOrNull() ?: return null
            return runCatching {
                Json { ignoreUnknownKeys = true; isLenient = true }
                    .decodeFromString(ReviewParseResult.serializer(), text)
            }.getOrNull()
        }
    }
}
