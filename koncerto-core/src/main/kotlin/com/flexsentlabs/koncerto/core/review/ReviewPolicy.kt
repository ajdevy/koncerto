package com.flexsentlabs.koncerto.core.review

/**
 * All tunable review-quality knobs for one stage, parsed from the `review:` block of a
 * WORKFLOW.md stage. Every field defaults to preserving current behavior so existing
 * deployments are unaffected until they opt in (architecture-review-quality.md §9).
 */
@kotlinx.serialization.Serializable
data class ReviewPolicy(
    val mode: ReviewMode = ReviewMode.BLOCKING,
    /** Diffs whose every file matches one of these are skipped entirely (eligibility). */
    val skipGlobs: List<String> = DEFAULT_SKIP_GLOBS,
    /** Any changed file matching these forces the CRITICAL risk tier. */
    val criticalGlobs: List<String> = emptyList(),
    val largeChangeLoc: Int = 500,
    val manyFiles: Int = 15,
    /** Publish a finding only if its confidence ≥ threshold for its severity. */
    val publicationThresholds: Map<Severity, Double> = DEFAULT_THRESHOLDS,
    val contextBudgetChars: Int = 60_000,
    val reviewInvariantsPath: String = "review-invariants.md",
    /** Optional per-tier model override; absent tiers use the stage model. */
    val tierModels: Map<RiskTier, String> = emptyMap(),
    /** Specialist prompt paths for the multi-agent critical tier (Epic 23). Empty = disabled. */
    val specialists: List<String> = emptyList(),
    val perRunTokenCap: Int? = null
) {
    fun modelForTier(tier: RiskTier): String? = tierModels[tier]

    fun thresholdFor(severity: Severity): Double =
        publicationThresholds[severity] ?: DEFAULT_THRESHOLDS.getValue(severity)

    companion object {
        val DEFAULT_SKIP_GLOBS = listOf(
            ".koncerto/**",
            ".review-*",
            "**/.review-*",
            "**/*.lock",
            "*.lock",
            "**/generated/**",
            "**/node_modules/**"
        )

        val DEFAULT_THRESHOLDS = mapOf(
            Severity.CRITICAL to 0.5,
            Severity.WARNING to 0.7,
            Severity.SUGGESTION to 0.85
        )

        /** Documentation-only extensions: a diff of only these skips review. */
        val DOC_EXTENSIONS = setOf("md", "markdown", "txt", "rst", "adoc")

        val DEFAULT = ReviewPolicy()
    }
}
