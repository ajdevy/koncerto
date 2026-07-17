package com.flexsentlabs.koncerto.core.review

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class ReviewOutputParserTest {

    @Test
    fun `parses fenced findings block from plain text`() {
        val text = """
            ✅ **Approved** — no blockers.
            **0 blocking · 1 warnings · 0 suggestions** · 2 files

            ```review-findings
            {"findings":[{"seq":1,"category":"correctness","severity":"warning","confidence":0.6,"file":"A.kt","line":10,"description":"x","expectedAction":"y","evidence":"z"}]}
            ```
        """.trimIndent()

        val result = ReviewOutputParser.parse(text, promptVersion = "2.0")

        assertThat(result.parseStatus).isEqualTo(ParseStatus.OK)
        assertThat(result.verdictPass).isTrue()
        assertThat(result.findings).hasSize(1)
        assertThat(result.findings[0].severity).isEqualTo(Severity.WARNING)
        assertThat(result.promptVersion).isEqualTo("2.0")
    }

    @Test
    fun `critical finding makes verdict fail`() {
        val text = """
            ❌ **Changes requested**
            ```review-findings
            {"findings":[{"seq":1,"category":"security","severity":"critical","confidence":0.9,"description":"boom"}]}
            ```
        """.trimIndent()

        val result = ReviewOutputParser.parse(text)
        assertThat(result.verdictPass).isFalse()
        assertThat(result.findings).hasSize(1)
    }

    @Test
    fun `parses CLI json envelope with usage`() {
        val envelope = """
            {"type":"result","result":"✅ **Approved**\n\n```review-findings\n{\"findings\":[]}\n```","usage":{"input_tokens":1200,"output_tokens":340},"duration_ms":5000,"is_error":false}
        """.trimIndent()

        val result = ReviewOutputParser.parse(envelope)

        assertThat(result.parseStatus).isEqualTo(ParseStatus.OK)
        assertThat(result.verdictPass).isTrue()
        assertThat(result.usage.inputTokens).isEqualTo(1200L)
        assertThat(result.usage.outputTokens).isEqualTo(340L)
        assertThat(result.usage.totalTokens).isEqualTo(1540L)
        assertThat(result.usage.durationMs).isEqualTo(5000L)
    }

    @Test
    fun `falls back to verdict string when no findings block`() {
        val text = "❌ FAIL — something broke and there is no JSON block here"
        val result = ReviewOutputParser.parse(text)

        assertThat(result.parseStatus).isEqualTo(ParseStatus.FALLBACK)
        assertThat(result.verdictPass).isFalse()
        assertThat(result.findings).hasSize(0)
    }

    @Test
    fun `fallback treats approved text as pass`() {
        val result = ReviewOutputParser.parse("✅ **Approved** — looks good, no structured block")
        assertThat(result.parseStatus).isEqualTo(ParseStatus.FALLBACK)
        assertThat(result.verdictPass).isTrue()
    }

    @Test
    fun `humanText strips the machine findings block`() {
        val text = """
            ✅ **Approved** — all good.

            ```review-findings
            {"findings":[]}
            ```
        """.trimIndent()

        val result = ReviewOutputParser.parse(text)

        assertThat(result.humanText.contains("review-findings")).isEqualTo(false)
        assertThat(result.humanText.contains("✅ **Approved**")).isTrue()
    }

    @Test
    fun `humanText unwraps json envelope so PR comments are not raw json`() {
        val envelope = """
            {"type":"result","result":"✅ **Approved** — no blockers.\n\n```review-findings\n{\"findings\":[]}\n```","usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()

        val result = ReviewOutputParser.parse(envelope)

        assertThat(result.humanText.startsWith("✅ **Approved**")).isTrue()
        assertThat(result.humanText.contains("input_tokens")).isEqualTo(false)
        assertThat(result.humanText.contains("review-findings")).isEqualTo(false)
    }

    @Test
    fun `malformed findings json degrades to fallback`() {
        val text = """
            ❌ **Changes requested**
            ```review-findings
            {this is not json at all
            ```
        """.trimIndent()
        val result = ReviewOutputParser.parse(text)
        assertThat(result.parseStatus).isEqualTo(ParseStatus.FALLBACK)
        assertThat(result.verdictPass).isFalse()
    }
}
