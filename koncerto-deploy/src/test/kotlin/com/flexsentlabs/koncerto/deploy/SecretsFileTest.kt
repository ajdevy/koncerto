package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SecretsFileTest {

    @Test
    fun `load parses key=value skipping blanks comments and empty values`(@TempDir dir: Path) {
        val file = dir.resolve("secrets.env")
        Files.writeString(file, """
            # secrets
            BREVO_API_KEY=xkeysib-abc123

            export DEBUG_TOKEN="tok-42"
            EMPTY=
        """.trimIndent())

        val loaded = SecretsFile.load(file.toString())
        assertThat(loaded["BREVO_API_KEY"]).isEqualTo("xkeysib-abc123")
        assertThat(loaded["DEBUG_TOKEN"]).isEqualTo("tok-42")
        assertThat(loaded.containsKey("EMPTY")).isEqualTo(false)
    }

    @Test
    fun `load returns empty for null or missing path`(@TempDir dir: Path) {
        assertThat(SecretsFile.load(null)).isEmpty()
        assertThat(SecretsFile.load(dir.resolve("nope.env").toString())).isEmpty()
    }

    @Test
    fun `mask never reveals the full secret`() {
        val masked = SecretsFile.mask("supersecretvalue1234567890")
        assertThat(masked).isEqualTo("supe…")
        assertThat(masked).doesNotContain("secretvalue")
        // Short values are hidden entirely — never shown in full with just an ellipsis appended.
        assertThat(SecretsFile.mask("ab")).isEqualTo("…")
        assertThat(SecretsFile.mask("abcde")).isEqualTo("…")
        assertThat(SecretsFile.mask("secret7")).isEqualTo("…")
    }
}
