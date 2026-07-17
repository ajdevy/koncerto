package com.flexsentlabs.koncerto.metrics

/**
 * Persistence contract for review-quality telemetry (Epic 18). Kept separate from
 * [MetricsRepository] (interface segregation, D-9) though the same SQLite class implements
 * both against the same DB file.
 */
interface ReviewMetricsRepository {

    suspend fun recordRun(run: ReviewRunRecord)

    suspend fun recordFindings(findings: List<ReviewFindingRecord>)

    /** Sets the outcome of a finding (fix-agent self-report or re-review corroboration). */
    suspend fun updateOutcome(findingId: String, outcome: String, outcomeSource: String)

    /** Sets a human accept/reject/false-positive label; overrides inferred outcome. */
    suspend fun setHumanLabel(findingId: String, label: String)

    suspend fun runs(projectSlug: String?, limit: Int = 200): List<ReviewRunRecord>

    suspend fun findingsForRun(runId: String): List<ReviewFindingRecord>

    /** All findings published in the window, for outcome matching + calibration. */
    suspend fun publishedFindingsForIssue(issueId: String): List<ReviewFindingRecord>

    suspend fun baseline(projectSlug: String?, windowDays: Int = 30): ReviewBaseline
}

data class ReviewRunRecord(
    val runId: String,
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String?,
    val attempt: Int,
    val commitSha: String?,
    val prNumber: Int?,
    val model: String?,
    val promptVersion: String?,
    val riskTier: String?,          // low|standard|critical
    val reviewMode: String?,        // advisory|blocking
    val eligibility: String,        // reviewed|skipped_<reason>
    val parseStatus: String?,       // ok|fallback
    val verdict: String?,           // pass|fail|skipped
    val findingsTotal: Int,
    val findingsPublished: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val durationMs: Long,
    val contextPackJson: String?,
    val createdAt: String
)

data class ReviewFindingRecord(
    val findingId: String,          // {runId}-{seq}
    val runId: String,
    val seq: Int,
    val specialist: String?,
    val category: String,
    val severity: String,
    val confidence: Double?,
    val file: String?,
    val line: Int?,
    val description: String,
    val expectedAction: String?,
    val evidence: String?,
    val published: Boolean,
    val dropReason: String?,
    val outcome: String?,
    val outcomeSource: String?,
    val humanLabel: String?,
    val issueId: String,
    val updatedAt: String
)

/**
 * Derived, computed-not-stored aggregates for the baseline/calibration report (Story 18.4).
 * HEO = high-evidence-outcome rate; FP = false-positive rate on human-labeled findings.
 */
data class ReviewBaseline(
    val windowDays: Int,
    val totalRuns: Int,
    val reviewedRuns: Int,
    val skippedRuns: Int,
    val fallbackRuns: Int,
    val totalFindings: Int,
    val publishedFindings: Int,
    val highEvidenceOutcomes: Int,
    val highEvidenceRate: Double,
    val humanLabeled: Int,
    val falsePositives: Int,
    val falsePositiveRate: Double,
    val totalTokens: Long,
    val tokensPerUsefulFinding: Double,
    val findingsByCategory: Map<String, Int>,
    val fpByCategory: Map<String, Int>
)
