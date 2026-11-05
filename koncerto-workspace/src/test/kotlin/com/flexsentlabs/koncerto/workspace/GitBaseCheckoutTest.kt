package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

class GitBaseCheckoutTest {
    private fun git(dir: Path, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git setup failed: ${args.firstOrNull()}" }
        return output.trim()
    }
    private fun logger() = StructuredLogger(listOf(object : LogSink { override fun write(line: String) {} }))

    @Test
    fun `new issue branch starts from the configured remote base`() {
        val root = Files.createTempDirectory("git-base-checkout-")
        val remote = root.resolve("origin.git")
        val seed = root.resolve("seed")
        val workspace = root.resolve("workspace")
        Files.createDirectories(seed)
        Files.createDirectories(workspace)
        git(root, "init", "--bare", remote.toString())
        git(seed, "init", "--initial-branch=main")
        git(seed, "config", "user.name", "Test")
        git(seed, "config", "user.email", "test@example.invalid")
        seed.resolve("project-source.txt").writeText("authoritative base")
        git(seed, "add", "project-source.txt")
        git(seed, "commit", "-m", "project base")
        val base = git(seed, "rev-parse", "HEAD")
        git(seed, "remote", "add", "origin", remote.toString())
        git(seed, "push", "origin", "main")
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = remote.toString(), prBase = "main"), logger())
        workflow.createBranch(workspace, "NEW-1")
        assertThat(workspace.resolve("project-source.txt").readText()).isEqualTo("authoritative base")
        assertThat(git(workspace, "rev-parse", "HEAD")).isEqualTo(base)
        assertThat(git(workspace, "branch", "--show-current")).isEqualTo("feature/NEW-1")
    }

    @Test
    fun `unavailable configured remote fails instead of inventing a repository`() {
        val workspace = Files.createTempDirectory("git-base-unavailable-")
        val missing = workspace.resolve("missing-remote.git")
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = missing.toString(), prBase = "main"), logger())
        val outcome = runCatching { workflow.createBranch(workspace, "NEW-2") }
        assertThat(outcome.isFailure).isTrue()
    }
}
