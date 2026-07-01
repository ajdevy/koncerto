package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class KoncertoArtifactIgnoreTest {

    @TempDir
    lateinit var repoDir: Path

    @BeforeEach
    fun setUp() {
        ProcessBuilder("git", "init", "--initial-branch=main")
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        runGit("config", "user.email", "test@test.com")
        runGit("config", "user.name", "Test")
        repoDir.resolve("README.md").writeText("# test")
        runGit("add", "-A")
        runGit("commit", "-m", "initial")
    }

    private fun runGit(vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = pb.inputStream.bufferedReader().readText()
        pb.waitFor()
        return out.trim()
    }

    @Test
    fun `ensureGitignore appends koncerto block to existing gitignore`() {
        repoDir.resolve(".gitignore").writeText("*.log\n")
        KoncertoArtifactIgnore.ensureGitignore(repoDir)
        val content = repoDir.resolve(".gitignore").readText()
        assertThat(content).contains("*.log")
        assertThat(content).contains(KoncertoArtifactIgnore.MARKER)
        assertThat(content).contains(".koncerto/")
        assertThat(content).contains(".review-*")
    }

    @Test
    fun `ensureGitignore is idempotent`() {
        KoncertoArtifactIgnore.ensureGitignore(repoDir)
        KoncertoArtifactIgnore.ensureGitignore(repoDir)
        val content = repoDir.resolve(".gitignore").readText()
        assertThat(content.lines().count { it == KoncertoArtifactIgnore.MARKER }).isEqualTo(1)
    }

    @Test
    fun `commitAndPush excludes koncerto artifacts`() {
        val config = com.flexsentlabs.koncerto.core.config.GitConfig(
            enabled = true,
            autoCommit = true,
            autoPush = false
        )
        val logs = mutableListOf<String>()
        val workflow = GitWorkflow(config, com.flexsentlabs.koncerto.logging.StructuredLogger(
            listOf(object : com.flexsentlabs.koncerto.logging.LogSink {
                override fun write(line: String) { logs.add(line) }
            })
        ))
        workflow.createBranch(repoDir, "FLE-52")

        val koncertoDir = repoDir.resolve(".koncerto")
        Files.createDirectories(koncertoDir)
        koncertoDir.resolve("dispatch-trace-2026-06-30.jsonl").writeText("""{"step":"dispatch"}""")
        repoDir.resolve(".review-output").writeText("review text")
        repoDir.resolve(".review-status").writeText("pass")
        repoDir.resolve("app.py").writeText("print('hello')")

        workflow.commitAndPush(repoDir, "FLE-52", "Register with email", labels = listOf("feat"))

        val tracked = runGit("ls-files")
        assertThat(tracked).contains("app.py")
        assertThat(tracked).doesNotContain(".koncerto/dispatch-trace-2026-06-30.jsonl")
        assertThat(tracked).doesNotContain(".review-output")
        assertThat(tracked).doesNotContain(".review-status")
    }

    @Test
    fun `commitAndPush untracks previously committed koncerto artifacts`() {
        val koncertoDir = repoDir.resolve(".koncerto")
        Files.createDirectories(koncertoDir)
        koncertoDir.resolve("review-trace.jsonl").writeText("trace")
        repoDir.resolve(".review-attempt").writeText("1")
        runGit("add", "-A")
        runGit("commit", "-m", "accidentally committed pipeline files")

        val config = com.flexsentlabs.koncerto.core.config.GitConfig(
            enabled = true,
            autoCommit = true,
            autoPush = false
        )
        val workflow = GitWorkflow(config, com.flexsentlabs.koncerto.logging.StructuredLogger(emptyList()))
        workflow.createBranch(repoDir, "FLE-52")
        repoDir.resolve("feature.py").writeText("# feature")
        workflow.commitAndPush(repoDir, "FLE-52", "Actual feature", labels = emptyList())

        val tracked = runGit("ls-files")
        assertThat(tracked).contains("feature.py")
        assertThat(tracked).doesNotContain(".koncerto/review-trace.jsonl")
        assertThat(tracked).doesNotContain(".review-attempt")
    }
}
