package com.flexsentlabs.koncerto.core.review

/**
 * Pure review-routing decisions (Epic 19). All functions take plain data (changed file
 * paths, diff stats, policy) and return a decision — no IO — so they are trivially testable
 * and identical whether invoked from the orchestrator or an e2e harness.
 */

enum class EligibilityOutcome { REVIEWED, SKIPPED }

data class EligibilityDecision(
    val outcome: EligibilityOutcome,
    /** e.g. "reviewed", "skipped_no_changes", "skipped_artifact_only", "skipped_docs_only". */
    val reason: String
) {
    val shouldReview: Boolean get() = outcome == EligibilityOutcome.REVIEWED
}

object ReviewEligibility {

    fun evaluate(changedFiles: List<String>, policy: ReviewPolicy = ReviewPolicy.DEFAULT): EligibilityDecision {
        val files = changedFiles.map { it.trim().trimStart('/') }.filter { it.isNotBlank() }
        if (files.isEmpty()) {
            return EligibilityDecision(EligibilityOutcome.SKIPPED, "skipped_no_changes")
        }
        // A critical-path file always warrants review, regardless of artifact/doc heuristics.
        val touchesCritical = files.any { Glob.matchesAny(policy.criticalGlobs, it) }
        if (touchesCritical) {
            return EligibilityDecision(EligibilityOutcome.REVIEWED, "reviewed")
        }
        if (files.all { Glob.matchesAny(policy.skipGlobs, it) }) {
            return EligibilityDecision(EligibilityOutcome.SKIPPED, "skipped_artifact_only")
        }
        if (files.all { isDoc(it) }) {
            return EligibilityDecision(EligibilityOutcome.SKIPPED, "skipped_docs_only")
        }
        return EligibilityDecision(EligibilityOutcome.REVIEWED, "reviewed")
    }

    private fun isDoc(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in ReviewPolicy.DOC_EXTENSIONS
    }
}

object RiskRouter {

    /**
     * @param changedFiles repo-relative paths
     * @param totalLinesChanged sum of added+deleted lines across the diff (git numstat)
     */
    fun classify(
        changedFiles: List<String>,
        totalLinesChanged: Int,
        policy: ReviewPolicy = ReviewPolicy.DEFAULT
    ): RiskTier {
        val files = changedFiles.map { it.trim().trimStart('/') }.filter { it.isNotBlank() }
        if (files.any { Glob.matchesAny(policy.criticalGlobs, it) }) {
            return RiskTier.CRITICAL
        }
        if (totalLinesChanged > policy.largeChangeLoc || files.size > policy.manyFiles) {
            return RiskTier.STANDARD
        }
        return RiskTier.LOW
    }
}
