package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    fun `needsAuth returns true for claude`() {
        assertThat(AgentAuthChecker.needsAuth("claude")).isTrue()
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
    fun `isAuthenticated returns true for claude when marked authenticated`() {
        AgentAuthChecker.markAuthenticated("claude")
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

    @Test
    fun `concurrent isAuthenticated calls do not throw ConcurrentModificationException`() {
        val threadCount = 20
        val pool = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(1)
        val errors = AtomicInteger(0)
        val tasks = (1..threadCount).map {
            pool.submit {
                try {
                    latch.await()
                    // markAuthenticated touches overrideAuth; isAuthenticated reads cache — must not race
                    AgentAuthChecker.markAuthenticated("opencode")
                    AgentAuthChecker.isAuthenticated("opencode")
                    AgentAuthChecker.reset()
                    AgentAuthChecker.isAuthenticated("claude")
                } catch (_: Exception) {
                    errors.incrementAndGet()
                }
            }
        }
        latch.countDown()
        tasks.forEach { it.get() }
        pool.shutdown()
        assertThat(errors.get() == 0).isTrue()
    }
}
