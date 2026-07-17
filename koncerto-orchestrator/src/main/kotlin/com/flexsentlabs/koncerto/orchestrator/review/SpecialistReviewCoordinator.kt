package com.flexsentlabs.koncerto.orchestrator.review

import com.flexsentlabs.koncerto.core.review.FindingMerge
import com.flexsentlabs.koncerto.core.review.ReviewFinding
import com.flexsentlabs.koncerto.core.review.ReviewParseResult
import com.flexsentlabs.koncerto.core.review.ReviewUsage

/**
 * Runs specialist reviewers for the critical risk tier (Epic 23) and merges their findings
 * into one deduplicated set. The model invocation is injected as [runSpecialist], so this
 * coordinator is testable with fakes and independent of the agent runtime. Findings are
 * tagged with their specialist; usage is summed across runs.
 *
 * **Sequential, deliberately.** Specialists share the issue's single workspace and each run
 * writes the same `.review-status` / `.review-findings.json` handoff files, so running them
 * concurrently would have them clobber each other's results. Parallel fan-out needs
 * per-specialist workspace isolation first; until that exists, correctness beats wall-clock.
 */
class SpecialistReviewCoordinator(
    private val runSpecialist: suspend (specialistPromptPath: String) -> ReviewParseResult?
) {

    data class SpecialistResult(
        val findings: List<ReviewFinding>,
        val usage: ReviewUsage,
        val verdictPass: Boolean,
        val specialistCount: Int
    )

    suspend fun run(specialistPromptPaths: List<String>): SpecialistResult {
        val results = specialistPromptPaths.mapNotNull { path ->
            val specialistName = specialistNameFor(path)
            runSpecialist(path)?.let { parsed ->
                parsed to parsed.findings.map { it.copy(specialist = specialistName) }
            }
        }

        val merged = FindingMerge.mergeAndDedup(results.map { it.second })
        val totalUsage = results.map { it.first.usage }.fold(ReviewUsage.EMPTY) { acc, u ->
            ReviewUsage(
                inputTokens = acc.inputTokens + u.inputTokens,
                outputTokens = acc.outputTokens + u.outputTokens,
                totalTokens = acc.totalTokens + u.totalTokens,
                durationMs = acc.durationMs + u.durationMs,   // sequential → elapsed time adds up
                isError = acc.isError || u.isError
            )
        }
        // Any critical finding from any specialist fails the combined verdict.
        val pass = merged.none { it.severity == com.flexsentlabs.koncerto.core.review.Severity.CRITICAL }
        return SpecialistResult(merged, totalUsage, pass, results.size)
    }

    private fun specialistNameFor(path: String): String =
        path.substringAfterLast('/').substringAfterLast("review-").substringBeforeLast('.')
}
