package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GitRemoteResolverTest {

    @Test
    fun `repoFullNameFromRemoteUrl parses https and ssh urls`() {
        assertThat(GitRemoteResolver.repoFullNameFromRemoteUrl("https://github.com/acme/widget.git"))
            .isEqualTo("acme/widget")
        assertThat(GitRemoteResolver.repoFullNameFromRemoteUrl("git@github.com:acme/widget.git"))
            .isEqualTo("acme/widget")
    }

    @Test
    fun `repoFullNameFromRemoteUrl returns null for blank or non-github url`() {
        assertThat(GitRemoteResolver.repoFullNameFromRemoteUrl("")).isNull()
        assertThat(GitRemoteResolver.repoFullNameFromRemoteUrl("https://gitlab.com/acme/widget.git")).isNull()
    }

    @Test
    fun `repoFullName reads origin from workspace git config`(@TempDir tmpDir: Path) {
        Files.createDirectories(tmpDir.resolve(".git"))
        Files.writeString(
            tmpDir.resolve(".git/config"),
            """
            [remote "origin"]
                url = https://github.com/acme/target-project.git
            """.trimIndent()
        )
        assertThat(GitRemoteResolver.repoFullName(tmpDir)).isEqualTo("acme/target-project")
    }

    @Test
    fun `sanitizeRemoteUrl strips embedded credentials`() {
        val sanitized = GitRemoteResolver.sanitizeRemoteUrl(
            "https://x-access-token:secret@github.com/acme/repo.git"
        )
        assertThat(sanitized).isEqualTo("https://github.com/acme/repo.git")
    }
}
