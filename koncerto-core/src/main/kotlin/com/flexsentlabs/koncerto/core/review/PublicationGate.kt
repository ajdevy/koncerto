package com.flexsentlabs.koncerto.core.review

/**
 * The publication gate (Epic 21): given scored findings, decide which are worth a human's
 * attention. Pure — the reviewer self-assigns severity+confidence, this applies the
 * deterministic threshold (architecture-review-quality.md §4.5, D-3).
 *
 * Findings with no self-reported confidence are published (favor recall); the missing score
 * is still recorded so calibration can flag prompts that omit it.
 */

data class GatedFinding(
    val finding: ReviewFinding,
    val published: Boolean,
    val dropReason: String?
)

data class GateResult(
    val gated: List<GatedFinding>
) {
    val published: List<ReviewFinding> get() = gated.filter { it.published }.map { it.finding }
    val dropped: List<GatedFinding> get() = gated.filter { !it.published }
}

object PublicationGate {

    fun apply(findings: List<ReviewFinding>, policy: ReviewPolicy = ReviewPolicy.DEFAULT): GateResult {
        val gated = findings.map { f ->
            val threshold = policy.thresholdFor(f.severity)
            val confidence = f.confidence
            when {
                confidence == null -> GatedFinding(f, published = true, dropReason = null)
                confidence >= threshold -> GatedFinding(f, published = true, dropReason = null)
                else -> GatedFinding(
                    f,
                    published = false,
                    dropReason = "below_threshold(confidence=%.2f<%.2f)".format(confidence, threshold)
                )
            }
        }
        return GateResult(gated)
    }
}

/**
 * Merge + dedup for the multi-agent critical tier (Epic 23). Concatenates specialist
 * findings, deduplicates by (file, line-bucket, category) keeping the highest-confidence
 * instance, and reassigns stable sequential `seq` values.
 */
object FindingMerge {

    private const val LINE_BUCKET = 10

    fun mergeAndDedup(findingLists: List<List<ReviewFinding>>): List<ReviewFinding> {
        val all = findingLists.flatten()
        val byKey = LinkedHashMap<String, ReviewFinding>()
        for (f in all) {
            val key = keyOf(f)
            val existing = byKey[key]
            if (existing == null || (f.confidence ?: 0.0) > (existing.confidence ?: 0.0)) {
                byKey[key] = f
            }
        }
        return byKey.values.mapIndexed { idx, f -> f.copy(seq = idx + 1) }
    }

    private fun keyOf(f: ReviewFinding): String {
        val file = f.file?.trim()?.trimStart('/')?.lowercase() ?: "?"
        val bucket = f.line?.let { it / LINE_BUCKET } ?: -1
        val category = f.category.trim().lowercase()
        return "$file|$bucket|$category"
    }

    /**
     * Does [candidate] plausibly refer to the same defect as [prior]? Used by outcome
     * tracking to mark a prior finding "likely_fixed" when a re-review no longer reports it.
     * Matches on same file + category with line within ±[LINE_BUCKET] (tolerates line drift
     * between the review commit and the fix commit).
     */
    fun sameDefect(prior: ReviewFinding, candidate: ReviewFinding): Boolean {
        val samesFile = prior.file?.trim()?.trimStart('/')?.lowercase() ==
            candidate.file?.trim()?.trimStart('/')?.lowercase()
        val sameCategory = prior.category.trim().lowercase() == candidate.category.trim().lowercase()
        if (!samesFile || !sameCategory) return false
        val a = prior.line ?: return true
        val b = candidate.line ?: return true
        return kotlin.math.abs(a - b) <= LINE_BUCKET
    }
}
