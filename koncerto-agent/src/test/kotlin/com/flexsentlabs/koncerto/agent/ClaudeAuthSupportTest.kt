package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.nio.file.Files
import org.junit.jupiter.api.Test

class ClaudeAuthSupportTest {
    @Test
    fun `extractToken returns token from wrapped oauth output`() {
        val output = """
            Opening browser to sign in...
            Your OAuth token (valid for 1 year):
            sk-ant-oat01-Usy0d4QLGA8z16UlVox-j9FNjn_fm4S-vXtHsBOUxBqaRBLDqjf9xNyXKk1uL
            NvRK3q3EAePTLu9jJE6qOokfw-3mRYPgAA
            Store this token securely.
        """.trimIndent()

        assertThat(ClaudeAuthSupport.extractToken(output)).isEqualTo(
            "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"
        )
    }

    @Test
    fun `extractToken returns null when oauth token is missing`() {
        assertThat(ClaudeAuthSupport.extractToken("no token here")).isNull()
    }

    @Test
    fun `saveToken and loadToken round trip`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-support-")
        val tokenPath = dir.resolve("token.txt")
        try {
            System.setProperty("koncerto.claude.auth.token.path", tokenPath.toString())
            ClaudeAuthSupport.saveToken("  sk-ant-oat01-test-token  ")
            assertThat(Files.readString(tokenPath)).isEqualTo("sk-ant-oat01-test-token")
            assertThat(ClaudeAuthSupport.loadToken()).isEqualTo("sk-ant-oat01-test-token")
        } finally {
            if (original == null) {
                System.clearProperty("koncerto.claude.auth.token.path")
            } else {
                System.setProperty("koncerto.claude.auth.token.path", original)
            }
        }
    }

    @Test
    fun `clearToken removes saved token file`() {
        val original = System.getProperty("koncerto.claude.auth.token.path")
        val dir = Files.createTempDirectory("claude-auth-clear-")
        val tokenPath = dir.resolve("token.txt")
        try {
            System.setProperty("koncerto.claude.auth.token.path", tokenPath.toString())
            ClaudeAuthSupport.saveToken("sk-ant-oat01-test-token")
            ClaudeAuthSupport.clearToken()
            assertThat(ClaudeAuthSupport.loadToken()).isNull()
        } finally {
            if (original == null) {
                System.clearProperty("koncerto.claude.auth.token.path")
            } else {
                System.setProperty("koncerto.claude.auth.token.path", original)
            }
        }
    }
}
