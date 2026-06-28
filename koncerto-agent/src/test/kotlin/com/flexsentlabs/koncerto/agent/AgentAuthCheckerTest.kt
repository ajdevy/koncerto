package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import java.nio.file.Files
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
    fun `isAuthenticated returns true for claude when oauth token is stored`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-checker-")
        val tokenPath = dir.resolve("token.txt")
        try {
            System.setProperty("koncerto.claude.auth.token.path", tokenPath.toString())
            ClaudeAuthSupport.saveToken("sk-ant-oat01-test-token")
            AgentAuthChecker.reset()
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
        } finally {
            if (original == null) {
                System.clearProperty("koncerto.claude.auth.token.path")
            } else {
                System.setProperty("koncerto.claude.auth.token.path", original)
            }
        }
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
    fun `markUnauthenticated overrides cached auth state`() {
        AgentAuthChecker.markAuthenticated("claude")
        AgentAuthChecker.markUnauthenticated("claude")
        assertThat(AgentAuthChecker.isAuthenticated("claude")).isFalse()
    }

    @Test
    fun `setClaudeAuthToken stores token and marks authenticated`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-set-")
        try {
            System.setProperty("koncerto.claude.auth.token.path", dir.resolve("token.txt").toString())
            AgentAuthChecker.reset()
            AgentAuthChecker.setClaudeAuthToken("sk-ant-oat01-test")
            assertThat(AgentAuthChecker.getClaudeAuthToken()).isEqualTo("sk-ant-oat01-test")
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
        } finally {
            if (original == null) System.clearProperty("koncerto.claude.auth.token.path")
            else System.setProperty("koncerto.claude.auth.token.path", original)
            AgentAuthChecker.reset()
        }
    }

    @Test
    fun `deprecated api key accessors delegate to token methods`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-deprecated-")
        try {
            System.setProperty("koncerto.claude.auth.token.path", dir.resolve("token.txt").toString())
            AgentAuthChecker.reset()
            AgentAuthChecker.setClaudeApiKey("legacy-key")
            assertThat(AgentAuthChecker.getClaudeApiKey()).isEqualTo("legacy-key")
        } finally {
            if (original == null) System.clearProperty("koncerto.claude.auth.token.path")
            else System.setProperty("koncerto.claude.auth.token.path", original)
            AgentAuthChecker.reset()
        }
    }

    @Test
    fun `reset clears override auth state`() {
        AgentAuthChecker.markAuthenticated("codex")
        AgentAuthChecker.reset()
        AgentAuthChecker.markUnauthenticated("codex")
        assertThat(AgentAuthChecker.isAuthenticated("codex")).isFalse()
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

    @org.junit.jupiter.api.AfterEach
    fun resetSeams() {
        AgentAuthChecker.testAuthProcessFactory = null
        AgentAuthChecker.reset()
    }

    @Test
    fun `isAuthenticated uses codex login status process when not overridden`() {
        AgentAuthChecker.reset()
        AgentAuthChecker.testAuthProcessFactory = { _ ->
            ProcessBuilder("bash", "-c", "exit 0")
        }
        assertThat(AgentAuthChecker.isAuthenticated("codex")).isTrue()
    }

    @Test
    fun `isAuthenticated uses claude auth status json when token absent`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-probe-")
        try {
            System.setProperty("koncerto.claude.auth.token.path", dir.resolve("missing.txt").toString())
            AgentAuthChecker.reset()
            ClaudeAuthSupport.clearToken()
            AgentAuthChecker.testAuthProcessFactory = { _ ->
                ProcessBuilder("bash", "-c", "echo '{\"loggedIn\": true}'; exit 0")
            }
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isTrue()
        } finally {
            AgentAuthChecker.testAuthProcessFactory = null
            if (original == null) System.clearProperty("koncerto.claude.auth.token.path")
            else System.setProperty("koncerto.claude.auth.token.path", original)
            AgentAuthChecker.reset()
        }
    }

    @Test
    fun `isAuthenticated returns false when codex login status fails`() {
        AgentAuthChecker.reset()
        AgentAuthChecker.testAuthProcessFactory = { _ ->
            ProcessBuilder("bash", "-c", "exit 1")
        }
        assertThat(AgentAuthChecker.isAuthenticated("codex")).isFalse()
    }

    @Test
    fun `isAuthenticated returns false when claude auth probe times out`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-timeout-")
        try {
            System.setProperty("koncerto.claude.auth.token.path", dir.resolve("missing.txt").toString())
            AgentAuthChecker.reset()
            ClaudeAuthSupport.clearToken()
            AgentAuthChecker.testAuthProcessFactory = { _ ->
                ProcessBuilder("bash", "-c", "sleep 10")
            }
            assertThat(AgentAuthChecker.isAuthenticated("claude")).isFalse()
        } finally {
            AgentAuthChecker.testAuthProcessFactory = null
            if (original == null) System.clearProperty("koncerto.claude.auth.token.path")
            else System.setProperty("koncerto.claude.auth.token.path", original)
            AgentAuthChecker.reset()
        }
    }

    @Test
    fun `authProcessBuilder uses test factory when configured`() {
        var seen: String? = null
        AgentAuthChecker.testAuthProcessFactory = { cmd ->
            seen = cmd
            ProcessBuilder("bash", "-c", "exit 0")
        }
        AgentAuthChecker.authProcessBuilder("echo probe")
        assertThat(seen).isEqualTo("echo probe")
    }
}
