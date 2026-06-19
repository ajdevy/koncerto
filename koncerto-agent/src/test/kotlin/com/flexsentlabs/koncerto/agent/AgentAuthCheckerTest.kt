package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class AgentAuthCheckerTest {

    @Test
    fun `needsAuth returns true for codex`() {
        assertThat(AgentAuthChecker.needsAuth("codex")).isTrue()
    }

    @Test
    fun `needsAuth is case insensitive`() {
        assertThat(AgentAuthChecker.needsAuth("CodeX")).isTrue()
    }

    @Test
    fun `needsAuth trims whitespace`() {
        assertThat(AgentAuthChecker.needsAuth("  codex  ")).isTrue()
    }

    @Test
    fun `needsAuth returns false for opencode`() {
        assertThat(AgentAuthChecker.needsAuth("opencode")).isFalse()
    }

    @Test
    fun `needsAuth returns false for claude`() {
        assertThat(AgentAuthChecker.needsAuth("claude")).isFalse()
    }

    @Test
    fun `needsAuth returns false for empty string`() {
        assertThat(AgentAuthChecker.needsAuth("")).isFalse()
    }

    @Test
    fun `needsAuth returns false for unknown agent kinds`() {
        assertThat(AgentAuthChecker.needsAuth("custom-agent")).isFalse()
    }

    @Test
    fun `isAuthenticated returns true for opencode without side effects`() {
        assertThat(AgentAuthChecker.isAuthenticated("opencode")).isTrue()
    }

    @Test
    fun `isAuthenticated returns true for claude without side effects`() {
        assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
    }

    @Test
    fun `isAuthenticated returns true for empty string`() {
        assertThat(AgentAuthChecker.isAuthenticated("")).isTrue()
    }

    @Test
    fun `isAuthenticated is case insensitive`() {
        assertThat(AgentAuthChecker.isAuthenticated("Opencode")).isTrue()
    }
}
