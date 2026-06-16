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
    fun `empty template returns empty string`() {
        val out = PromptRenderer.render("", emptyMap())
        assertThat(out).isEqualTo("")
    }
}