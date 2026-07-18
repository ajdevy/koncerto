package com.flexsentlabs.koncerto.core.review

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

/**
 * Exhaustive coverage of the wire-parsing enums and value types. These map untrusted wire
 * strings (config, model output, dashboard input) to domain enums, so every alias and the
 * unknown/null fallbacks are exercised deliberately.
 */
class ReviewTypesTest {

    @Test
    fun `Severity fromWire covers every alias and fallback`() {
        assertThat(Severity.fromWire("critical")).isEqualTo(Severity.CRITICAL)
        assertThat(Severity.fromWire("BLOCKER")).isEqualTo(Severity.CRITICAL)
        assertThat(Severity.fromWire("blocking")).isEqualTo(Severity.CRITICAL)
        assertThat(Severity.fromWire("warning")).isEqualTo(Severity.WARNING)
        assertThat(Severity.fromWire("warn")).isEqualTo(Severity.WARNING)
        assertThat(Severity.fromWire("major")).isEqualTo(Severity.WARNING)
        assertThat(Severity.fromWire("minor")).isEqualTo(Severity.WARNING)
        assertThat(Severity.fromWire("suggestion")).isEqualTo(Severity.SUGGESTION)
        assertThat(Severity.fromWire("nit")).isEqualTo(Severity.SUGGESTION)
        assertThat(Severity.fromWire("info")).isEqualTo(Severity.SUGGESTION)
        assertThat(Severity.fromWire("style")).isEqualTo(Severity.SUGGESTION)
        assertThat(Severity.fromWire("  Critical  ")).isEqualTo(Severity.CRITICAL)
        assertThat(Severity.fromWire("nonsense")).isNull()
        assertThat(Severity.fromWire(null)).isNull()
    }

    @Test
    fun `RiskTier fromWire covers aliases and defaults to standard`() {
        assertThat(RiskTier.fromWire("low")).isEqualTo(RiskTier.LOW)
        assertThat(RiskTier.fromWire("CRITICAL")).isEqualTo(RiskTier.CRITICAL)
        assertThat(RiskTier.fromWire("standard")).isEqualTo(RiskTier.STANDARD)
        assertThat(RiskTier.fromWire("anything else")).isEqualTo(RiskTier.STANDARD)
        assertThat(RiskTier.fromWire(null)).isEqualTo(RiskTier.STANDARD)
    }

    @Test
    fun `ReviewMode fromWire defaults to blocking`() {
        assertThat(ReviewMode.fromWire("advisory")).isEqualTo(ReviewMode.ADVISORY)
        assertThat(ReviewMode.fromWire("blocking")).isEqualTo(ReviewMode.BLOCKING)
        assertThat(ReviewMode.fromWire("whatever")).isEqualTo(ReviewMode.BLOCKING)
        assertThat(ReviewMode.fromWire(null)).isEqualTo(ReviewMode.BLOCKING)
    }

    @Test
    fun `FindingOutcome fromWire and high-evidence classification`() {
        assertThat(FindingOutcome.fromWire("fixed")).isEqualTo(FindingOutcome.FIXED)
        assertThat(FindingOutcome.fromWire("likely_fixed")).isEqualTo(FindingOutcome.LIKELY_FIXED)
        assertThat(FindingOutcome.fromWire("wontfix")).isEqualTo(FindingOutcome.WONT_FIX)
        assertThat(FindingOutcome.fromWire("not_a_bug")).isEqualTo(FindingOutcome.NOT_A_BUG)
        assertThat(FindingOutcome.fromWire("false_positive")).isEqualTo(FindingOutcome.NOT_A_BUG)
        assertThat(FindingOutcome.fromWire("discussed")).isEqualTo(FindingOutcome.DISCUSSED)
        assertThat(FindingOutcome.fromWire("ignored")).isEqualTo(FindingOutcome.IGNORED)
        assertThat(FindingOutcome.fromWire("???")).isNull()

        assertThat(FindingOutcome.FIXED.isHighEvidence()).isTrue()
        assertThat(FindingOutcome.LIKELY_FIXED.isHighEvidence()).isTrue()
        assertThat(FindingOutcome.DISCUSSED.isHighEvidence()).isTrue()
        assertThat(FindingOutcome.WONT_FIX.isHighEvidence()).isFalse()
        assertThat(FindingOutcome.NOT_A_BUG.isHighEvidence()).isFalse()
        assertThat(FindingOutcome.IGNORED.isHighEvidence()).isFalse()
    }

    @Test
    fun `HumanLabel fromWire covers aliases`() {
        assertThat(HumanLabel.fromWire("accept")).isEqualTo(HumanLabel.ACCEPT)
        assertThat(HumanLabel.fromWire("thumbs_up")).isEqualTo(HumanLabel.ACCEPT)
        assertThat(HumanLabel.fromWire("reject")).isEqualTo(HumanLabel.REJECT)
        assertThat(HumanLabel.fromWire("down")).isEqualTo(HumanLabel.REJECT)
        assertThat(HumanLabel.fromWire("false_positive")).isEqualTo(HumanLabel.FALSE_POSITIVE)
        assertThat(HumanLabel.fromWire("fp")).isEqualTo(HumanLabel.FALSE_POSITIVE)
        assertThat(HumanLabel.fromWire("maybe")).isNull()
        assertThat(HumanLabel.fromWire(null)).isNull()
    }

    @Test
    fun `ReviewParseResult exposes parse status from the stored name`() {
        val ok = ReviewParseResult(verdictPass = true, parseStatusName = ParseStatus.OK.name)
        val fb = ReviewParseResult(verdictPass = false, parseStatusName = ParseStatus.FALLBACK.name)
        assertThat(ok.parseStatus).isEqualTo(ParseStatus.OK)
        assertThat(fb.parseStatus).isEqualTo(ParseStatus.FALLBACK)
    }

    @Test
    fun `ReviewUsage EMPTY has zero totals`() {
        assertThat(ReviewUsage.EMPTY.totalTokens).isEqualTo(0L)
        assertThat(ReviewUsage.EMPTY.isError).isFalse()
    }

    @Test
    fun `ReviewPolicy resolves per-tier model and per-severity threshold`() {
        val policy = ReviewPolicy(
            tierModels = mapOf(RiskTier.CRITICAL to "big-model"),
            publicationThresholds = mapOf(Severity.CRITICAL to 0.3)
        )
        assertThat(policy.modelForTier(RiskTier.CRITICAL)).isEqualTo("big-model")
        assertThat(policy.modelForTier(RiskTier.LOW)).isNull()
        assertThat(policy.thresholdFor(Severity.CRITICAL)).isEqualTo(0.3)
        // Falls back to the built-in default for a severity not overridden.
        assertThat(policy.thresholdFor(Severity.WARNING)).isEqualTo(ReviewPolicy.DEFAULT_THRESHOLDS.getValue(Severity.WARNING))
    }
}
