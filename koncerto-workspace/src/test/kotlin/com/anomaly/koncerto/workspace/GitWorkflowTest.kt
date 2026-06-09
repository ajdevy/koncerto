package com.anomaly.koncerto.workspace

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.readText
import kotlin.io.path.writeText

@OptIn(ExperimentalPathApi::class)
class GitWorkflowTest {

    private lateinit var repoDir: Path
    private lateinit var logs: MutableList<String>

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) { logs.add(line) }
    }))

    @BeforeEach
    fun setUp() {
        repoDir = Files.createTempDirectory("git-workflow-")
        logs = mutableListOf()
        initGitRepo()
        Files.createDirectories(repoDir.resolve("feature"))
    }

    private fun initGitRepo() {
        val pb = ProcessBuilder("git", "init")
            .directory(repoDir.toFile())
            .redirectErrorStream(true)
            .start()
        pb.waitFor()
        // Set identity for commits
        runGit("config", "user.email", "test@test.com")
        runGit("config", "user.name", "Test")
        // Initial commit so we can create branches
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
    fun `createBranch creates new branch`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(repoDir, "ABC-1")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/ABC-1")
    }

    @Test
    fun `createBranch checks out existing branch`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())

        runGit("checkout", "-b", "feature/EXISTING")
        repoDir.resolve("existing.txt").writeText("hello")
        runGit("add", "-A")
        runGit("commit", "-m", "existing work")
        runGit("checkout", "main")

        workflow.createBranch(repoDir, "EXISTING")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/EXISTING")
    }

    @Test
    fun `createBranch does nothing when disabled`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(repoDir, "ABC-1")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("main")
    }

    @Test
    fun `commitAndPush when disabled does nothing`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())

        repoDir.resolve("test.txt").writeText("new content")
        workflow.commitAndPush(repoDir, "ABC-1", "Test title")

        // Verify no commit was made
        val log = runGit("log", "--oneline")
        assertThat(log.lines().size).isEqualTo(1) // only initial commit
    }

    @Test
    fun `commitAndPush creates commit`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())

        repoDir.resolve("test.txt").writeText("new content")
        workflow.commitAndPush(repoDir, "ABC-1", "Test title")

        val log = runGit("log", "--oneline")
        assertThat(log).contains("ABC-1: Test title")
    }

    @Test
    fun `createPullRequest returns null when disabled`() {
        val config = GitConfig(enabled = true, createPr = false)
        val workflow = GitWorkflow(config, noopLogger())

        val url = workflow.createPullRequest(repoDir, "ABC-1", "title", "desc")
        assertThat(url).isNull()
    }

    @Test
    fun `not a git repo logs warning and skips`() {
        val nonRepoDir = Files.createTempDirectory("not-a-repo-")
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(nonRepoDir, "ABC-1")

        val hasWarning = logs.any { it.contains("git_not_a_repository") }
        assertThat(hasWarning).isEqualTo(true)
    }

    @Test
    fun `branchName constructs correctly`() {
        val config = GitConfig(enabled = true, branchPrefix = "fix/")
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.branchName("ABC-1")).isEqualTo("fix/ABC-1")
    }

    @Test
    fun `branchName with empty prefix`() {
        val config = GitConfig(enabled = true, branchPrefix = "")
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.branchName("ABC-1")).isEqualTo("ABC-1")
    }
}
