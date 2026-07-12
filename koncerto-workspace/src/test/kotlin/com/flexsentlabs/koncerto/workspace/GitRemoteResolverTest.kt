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
    fun `repoFullName follows a worktree dot-git file to the real config`(@TempDir tmpDir: Path) {
        // A git worktree/submodule has `.git` as a FILE: "gitdir: <path>".
        val realGitDir = tmpDir.resolve("actual-gitdir").also { Files.createDirectories(it) }
        Files.writeString(
            realGitDir.resolve("config"),
            """
            [remote "origin"]
                url = git@github.com:acme/worktree-repo.git
            """.trimIndent()
        )
        val workspace = tmpDir.resolve("ws").also { Files.createDirectories(it) }
        Files.writeString(workspace.resolve(".git"), "gitdir: $realGitDir\n")

        assertThat(GitRemoteResolver.repoFullName(workspace)).isEqualTo("acme/worktree-repo")
    }

    @Test
    fun `repoFullName returns null when there is no git metadata`(@TempDir tmpDir: Path) {
        assertThat(GitRemoteResolver.repoFullName(tmpDir)).isNull()
    }

    @Test
    fun `repoFullName returns null when the dot-git file is not a gitdir pointer`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve(".git"), "not a gitdir pointer\n")
        assertThat(GitRemoteResolver.repoFullName(tmpDir)).isNull()
    }

    @Test
    fun `repoFullName returns null when the resolved config path is unreadable`(@TempDir tmpDir: Path) {
        // gitdir points at a directory whose "config" entry is itself a directory, so readString throws.
        val gitDir = tmpDir.resolve("gd").also { Files.createDirectories(it) }
        Files.createDirectories(gitDir.resolve("config"))
        Files.writeString(tmpDir.resolve(".git"), "gitdir: $gitDir\n")
        assertThat(GitRemoteResolver.repoFullName(tmpDir)).isNull()
    }

    @Test
    fun `resolveGitConfigPath returns null when reading the dot-git pointer throws`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve(".git"), "gitdir: /somewhere\n")
        val resolved = GitRemoteResolver.resolveGitConfigPath(tmpDir) { throw java.io.IOException("boom") }
        assertThat(resolved).isNull()
    }

    @Test
    fun `repoFullNameFromGitConfig returns null without an origin remote`() {
        assertThat(GitRemoteResolver.repoFullNameFromGitConfig("[core]\n\tbare = false\n")).isNull()
    }

    @Test
    fun `sanitizeRemoteUrl strips embedded credentials`() {
        val sanitized = GitRemoteResolver.sanitizeRemoteUrl(
            "https://x-access-token:secret@github.com/acme/repo.git"
        )
        assertThat(sanitized).isEqualTo("https://github.com/acme/repo.git")
    }
}
