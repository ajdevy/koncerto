package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GitCredentialSafetyTest {
    private fun logger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    private fun git(dir: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir.toFile())
            .redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git command failed: ${args.firstOrNull()}" }
        return output.trim()
    }

    @Test
    fun `origin does not persist the injected token`() {
        val repo = Files.createTempDirectory("git-auth-safety-")
        git(repo, "init")
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = "https://github.com/acme/repo.git"), logger())
        GitWorkflow.testGhTokenOverride = "synthetic-test-secret-12345"
        try {
            val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
            method.isAccessible = true
            method.invoke(workflow, repo)
            assertThat(git(repo, "remote", "get-url", "origin")).isEqualTo("https://github.com/acme/repo.git")
            assertThat(repo.resolve(".git/config").readText().contains("synthetic-test-secret-12345")).isFalse()
        } finally {
            GitWorkflow.testGhTokenOverride = null
        }
    }

    @Test
    fun `rejected push does not overwrite remote history`() {
        val root = Files.createTempDirectory("git-nonfastforward-")
        val remote = root.resolve("remote.git")
        val local = root.resolve("local")
        val other = root.resolve("other")
        Files.createDirectories(local)
        git(root, "init", "--bare", remote.toString())
        git(local, "init", "--initial-branch=main")
        git(local, "config", "user.email", "test@example.invalid")
        git(local, "config", "user.name", "Test")
        local.resolve("base.txt").writeText("base")
        git(local, "add", "base.txt")
        git(local, "commit", "-m", "base")
        git(local, "checkout", "-b", "feature/TASK-1")
        git(local, "remote", "add", "origin", remote.toString())
        git(local, "push", "-u", "origin", "feature/TASK-1")
        git(root, "clone", "--branch", "feature/TASK-1", remote.toString(), other.toString())
        git(other, "config", "user.email", "test@example.invalid")
        git(other, "config", "user.name", "Test")
        other.resolve("remote.txt").writeText("remote work")
        git(other, "add", "remote.txt")
        git(other, "commit", "-m", "remote commit")
        git(other, "push", "origin", "feature/TASK-1")
        val remoteHead = git(other, "rev-parse", "HEAD")
        local.resolve("local.txt").writeText("local work")
        git(local, "add", "local.txt")
        git(local, "commit", "-m", "local commit")
        val workflow = GitWorkflow(GitConfig(enabled = true, autoCommit = false, autoPush = true), logger())
        val outcome = runCatching { workflow.commitAndPush(local, "TASK-1", "Test") }
        assertThat(outcome.isFailure).isTrue()
        assertThat(git(other, "ls-remote", "origin", "refs/heads/feature/TASK-1").substringBefore('\t')).isEqualTo(remoteHead)
    }
}
