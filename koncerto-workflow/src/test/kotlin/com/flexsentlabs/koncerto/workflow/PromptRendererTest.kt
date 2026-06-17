package com.flexsentlabs.koncerto.workflow

import assertk.assertThat
import assertk.assertions.isEqualTo
import liqp.Template
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PromptRendererTest {

    @Test
    fun `renders simple variables`() {
        val out = PromptRenderer.render(
            "Hello {{ issue.identifier }} - {{ issue.title }}",
            mapOf("issue" to mapOf("identifier" to "ABC-1", "title" to "Fix bug"))
        )
        assertThat(out).isEqualTo("Hello ABC-1 - Fix bug")
    }

    @Test
    fun `renders attempt variable`() {
        val out = PromptRenderer.render("Run {{ attempt }}", mapOf("attempt" to 2))
        assertThat(out).isEqualTo("Run 2")
    }

    @Test
    fun `unknown variable fails rendering`() {
        assertThrows<IllegalStateException> {
            PromptRenderer.render("Hi {{ missing }}", emptyMap())
        }
    }

    @Test
    fun `nested unknown variable fails`() {
        assertThrows<IllegalStateException> {
            PromptRenderer.render("Hi {{ issue.missing }}", mapOf("issue" to mapOf("id" to "1")))
        }
    }

    @Test
    fun `subfield on scalar value fails`() {
        assertThrows<IllegalStateException> {
            PromptRenderer.render("{{ scalar.sub }}", mapOf("scalar" to "notamap"))
        }
    }

    @Test
    fun `empty template returns empty string`() {
        val out = PromptRenderer.render("", emptyMap())
        assertThat(out).isEqualTo("")
    }

    @Test
    fun `invalid template syntax throws`() {
        assertThrows<IllegalStateException> {
            PromptRenderer.render("{% badtag %}", emptyMap())
        }
    }

    @Test
    fun `nested Map value renders`() {
        val out = PromptRenderer.render("value: {{ nested.a }}", mapOf("nested" to mapOf("a" to 1)))
        assertThat(out).isEqualTo("value: 1")
    }

    @Test
    fun `Instant value renders`() {
        val out = PromptRenderer.render("{{ ts }}", mapOf("ts" to java.time.Instant.parse("2025-01-01T00:00:00Z")))
        assertThat(out).isEqualTo("2025-01-01T00:00:00Z")
    }
}