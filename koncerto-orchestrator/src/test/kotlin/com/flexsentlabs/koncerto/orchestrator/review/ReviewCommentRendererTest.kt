package com.flexsentlabs.koncerto.orchestrator.review

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.review.ReviewFinding
import com.flexsentlabs.koncerto.core.review.Severity
import org.junit.jupiter.api.Test

class ReviewCommentRendererTest {

    private fun finding(seq: Int, sev: Severity = Severity.CRITICAL, file: String = "A.kt", line: Int? = 10) =
        ReviewFinding(seq, "security", sev, 0.9, file, line, "desc $seq", "fix $seq", "evidence $seq")

    @Test
    fun `summary renders one entry per published finding with markers`() {
        val summary = ReviewCommentRenderer.renderSummary("run-1", listOf(finding(1), finding(2)))

        assertThat(summary.contains("<!-- koncerto-finding:run-1-1 -->")).isTrue()
        assertThat(summary.contains("<!-- koncerto-finding:run-1-2 -->")).isTrue()
        assertThat(summary.contains("desc 1")).isTrue()
        assertThat(summary.contains("desc 2")).isTrue()
    }

    @Test
    fun `summary is empty when nothing cleared the gate`() {
        // A review that publishes nothing must add nothing to the PR — silence is the feature.
        assertThat(ReviewCommentRenderer.renderSummary("run-1", emptyList())).isEqualTo("")
    }

    @Test
    fun `finding id round-trips through the marker`() {
        val body = ReviewCommentRenderer.renderFinding("run-7", finding(3))
        assertThat(ReviewCommentRenderer.findingIdOf(body)).isEqualTo("run-7-3")
        assertThat(ReviewCommentRenderer.isKoncertoAuthored(body)).isTrue()
    }

    @Test
    fun `human comment is not mistaken for koncerto-authored`() {
        assertThat(ReviewCommentRenderer.isKoncertoAuthored("nit: rename this please")).isEqualTo(false)
        assertThat(ReviewCommentRenderer.findingIdOf("nit: rename this please")).isEqualTo(null)
    }

    @Test
    fun `finding without a line still renders`() {
        val body = ReviewCommentRenderer.renderFinding("run-1", finding(1, line = null))
        assertThat(body.contains("`A.kt`")).isTrue()
        assertThat(body.contains("A.kt:")).isEqualTo(false)
    }

    @Test
    fun `severity icons distinguish all three levels`() {
        val critical = ReviewCommentRenderer.renderFinding("r", finding(1, Severity.CRITICAL))
        val warning = ReviewCommentRenderer.renderFinding("r", finding(2, Severity.WARNING))
        val suggestion = ReviewCommentRenderer.renderFinding("r", finding(3, Severity.SUGGESTION))
        assertThat(critical.contains("🔴")).isTrue()
        assertThat(warning.contains("🟡")).isTrue()
        assertThat(suggestion.contains("🔵")).isTrue()
    }

    @Test
    fun `finding without expected action or evidence still renders`() {
        val bare = ReviewFinding(1, "correctness", Severity.WARNING, null, "A.kt", 5, "just a description")
        val body = ReviewCommentRenderer.renderFinding("run-9", bare)
        assertThat(body.contains("just a description")).isTrue()
        assertThat(body.contains("**Fix:**")).isEqualTo(false)
        assertThat(body.contains("Evidence:")).isEqualTo(false)
    }
}
