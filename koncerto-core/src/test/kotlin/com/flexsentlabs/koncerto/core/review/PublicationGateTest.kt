package com.flexsentlabs.koncerto.core.review

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class PublicationGateTest {

    private fun finding(seq: Int, severity: Severity, confidence: Double?, file: String = "A.kt", line: Int = 1) =
        ReviewFinding(seq, "correctness", severity, confidence, file, line, "desc")

    @Test
    fun `high-confidence critical is published`() {
        val gate = PublicationGate.apply(listOf(finding(1, Severity.CRITICAL, 0.9)))
        assertThat(gate.published).hasSize(1)
    }

    @Test
    fun `low-confidence suggestion is dropped with reason`() {
        val gate = PublicationGate.apply(listOf(finding(1, Severity.SUGGESTION, 0.5)))
        assertThat(gate.published).hasSize(0)
        assertThat(gate.dropped).hasSize(1)
        assertThat(gate.dropped[0].dropReason).isNotNull()
    }

    @Test
    fun `critical just below default threshold is dropped`() {
        // default critical threshold 0.5
        val gate = PublicationGate.apply(listOf(finding(1, Severity.CRITICAL, 0.4)))
        assertThat(gate.published).hasSize(0)
    }

    @Test
    fun `missing confidence favors recall and publishes`() {
        val gate = PublicationGate.apply(listOf(finding(1, Severity.WARNING, null)))
        assertThat(gate.published).hasSize(1)
    }

    @Test
    fun `custom thresholds are honored`() {
        val policy = ReviewPolicy(publicationThresholds = mapOf(Severity.WARNING to 0.95))
        val gate = PublicationGate.apply(listOf(finding(1, Severity.WARNING, 0.9)), policy)
        assertThat(gate.published).hasSize(0)
    }

    // ---- FindingMerge ----

    @Test
    fun `dedup keeps highest confidence for same location and category`() {
        val a = finding(1, Severity.WARNING, 0.6, "A.kt", 10).copy(specialist = "security")
        val b = finding(1, Severity.WARNING, 0.9, "A.kt", 12).copy(specialist = "reliability")
        val merged = FindingMerge.mergeAndDedup(listOf(listOf(a), listOf(b)))
        assertThat(merged).hasSize(1)
        assertThat(merged[0].confidence).isEqualTo(0.9)
        assertThat(merged[0].seq).isEqualTo(1)
    }

    @Test
    fun `distinct categories at same location are kept separate`() {
        val a = finding(1, Severity.WARNING, 0.6, "A.kt", 10)
        val b = ReviewFinding(2, "security", Severity.CRITICAL, 0.8, "A.kt", 10, "d")
        val merged = FindingMerge.mergeAndDedup(listOf(listOf(a, b)))
        assertThat(merged).hasSize(2)
    }

    @Test
    fun `sameDefect tolerates line drift within bucket`() {
        val prior = finding(1, Severity.WARNING, 0.6, "A.kt", 10)
        val current = finding(5, Severity.WARNING, 0.6, "A.kt", 18)
        assertThat(FindingMerge.sameDefect(prior, current)).isTrue()
    }

    @Test
    fun `sameDefect false for different file`() {
        val prior = finding(1, Severity.WARNING, 0.6, "A.kt", 10)
        val current = finding(1, Severity.WARNING, 0.6, "B.kt", 10)
        assertThat(FindingMerge.sameDefect(prior, current)).isEqualTo(false)
    }
}
