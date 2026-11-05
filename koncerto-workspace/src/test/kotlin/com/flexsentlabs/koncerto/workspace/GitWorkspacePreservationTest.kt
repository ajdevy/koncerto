package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GitWorkspacePreservationTest {
    private fun git(dir: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0)
        return output.trim()
    }

    @Test
    fun `resuming the same branch keeps tracked and untracked work`() {
        val dir = Files.createTempDirectory("git-preserve-work-")
        git(dir, "init", "--initial-branch=main")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "user.email", "test@example.invalid")
        dir.resolve("tracked.txt").writeText("original")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-m", "base")
        git(dir, "checkout", "-b", "feature/TASK-1")
        dir.resolve("tracked.txt").writeText("unfinished tracked change")
        dir.resolve("untracked.txt").writeText("unfinished new file")
        val log = StructuredLogger(listOf(object : LogSink { override fun write(line: String) {} }))
        val workflow = GitWorkflow(GitConfig(enabled = true), log)
        workflow.createBranch(dir, "TASK-1")
        assertThat(dir.resolve("tracked.txt").readText()).isEqualTo("unfinished tracked change")
        assertThat(dir.resolve("untracked.txt").readText()).isEqualTo("unfinished new file")
    }
    @Test
    fun `resuming a branch keeps local commits ahead of origin`() {
        val root = Files.createTempDirectory("git-preserve-commits-")
        val remote = root.resolve("remote.git")
        val dir = root.resolve("repo")
        Files.createDirectories(dir)
        git(root, "init", "--bare", remote.toString())
        git(dir, "init", "--initial-branch=main")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "user.email", "test@example.invalid")
        dir.resolve("tracked.txt").writeText("original")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-m", "base")
        git(dir, "remote", "add", "origin", remote.toString())
        git(dir, "push", "origin", "main")
        git(dir, "checkout", "-b", "feature/TASK-1")
        git(dir, "push", "origin", "feature/TASK-1")
        dir.resolve("tracked.txt").writeText("local commit")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-m", "local work")
        val log = StructuredLogger(listOf(object : LogSink { override fun write(line: String) {} }))
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = remote.toString()), log)
        workflow.createBranch(dir, "TASK-1")
        assertThat(dir.resolve("tracked.txt").readText()).isEqualTo("local commit")
    }

    @Test
    fun `creating another issue branch does not rewrite local main`() {
        val root = Files.createTempDirectory("git-preserve-main-")
        val remote = root.resolve("remote.git")
        val dir = root.resolve("repo")
        Files.createDirectories(dir)
        git(root, "init", "--bare", remote.toString())
        git(dir, "init", "--initial-branch=main")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "user.email", "test@example.invalid")
        dir.resolve("tracked.txt").writeText("original")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-m", "base")
        git(dir, "remote", "add", "origin", remote.toString())
        git(dir, "push", "origin", "main")
        dir.resolve("tracked.txt").writeText("local main commit")
        git(dir, "add", "tracked.txt")
        git(dir, "commit", "-m", "local main work")
        val mainHead = git(dir, "rev-parse", "main")
        git(dir, "checkout", "-b", "other")
        val log = StructuredLogger(listOf(object : LogSink { override fun write(line: String) {} }))
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = remote.toString()), log)
        workflow.createBranch(dir, "TASK-2")
        assertThat(git(dir, "rev-parse", "main")).isEqualTo(mainHead)
    }

}
