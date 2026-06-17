package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
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
        val pb = ProcessBuilder("git", "init", "--initial-branch=main")
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

    @Test
    fun `commitPrefix returns fix when labels contain fix label`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("fix.txt").writeText("fix")
        workflow.commitAndPush(repoDir, "ABC-1", "Fix bug", labels = listOf("fix"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("fix: ABC-1: Fix bug")
    }

    @Test
    fun `commitPrefix returns feat when labels do not contain fix`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("feat.txt").writeText("feat")
        workflow.commitAndPush(repoDir, "ABC-1", "Add feature", labels = listOf("enhancement"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("feat: ABC-1: Add feature")
    }

    @Test
    fun `commitAndPush with autoPush creates commit and attempts push`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(repoDir, "ABC-1")
        repoDir.resolve("push-test.txt").writeText("push")
        workflow.commitAndPush(repoDir, "ABC-1", "Test push", labels = emptyList())
        val log = runGit("log", "--oneline")
        assertThat(log).contains("feat: ABC-1: Test push")
    }

    @Test
    fun `createPullRequest when enabled returns null when gh not available`() {
        val config = GitConfig(enabled = true, createPr = true, prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())
        val url = workflow.createPullRequest(repoDir, "ABC-1", "title", "description")
        // gh not installed in test env, so it should return null
        assertThat(url).isNull()
    }

    @Test
    fun `createPullRequest with null description`() {
        val config = GitConfig(enabled = true, createPr = true)
        val workflow = GitWorkflow(config, noopLogger())
        val url = workflow.createPullRequest(repoDir, "ABC-1", "title", null)
        assertThat(url).isNull()
    }

    @Test
    fun `not a git repo logs warning and skips in createBranch`() {
        val nonRepoDir = Files.createTempDirectory("not-a-repo-")
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(nonRepoDir, "ABC-1")
        val hasWarning = logs.any { it.contains("git_not_a_repository") }
        assertThat(hasWarning).isEqualTo(true)
    }

    @Test
    fun `createBranchFrom creates branch from source`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranchFrom(repoDir, "feature/new-branch", "main")
        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/new-branch")
    }

    @Test
    fun `createBranchFrom does nothing when disabled`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranchFrom(repoDir, "feature/new-branch", "main")
        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("main")
    }

    @Test
    fun `mergeBranch returns SUCCESS`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranchFrom(repoDir, "feature/merge-source", "main")
        repoDir.resolve("merge-test.txt").writeText("change")
        runGit("add", "-A")
        runGit("commit", "-m", "change on branch")
        runGit("checkout", "main")
        val result = workflow.mergeBranch(repoDir, "feature/merge-source", "main")
        assertThat(result).isEqualTo(MergeResult.SUCCESS)
    }

    @Test
    fun `mergeBranch does nothing when disabled`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())
        val result = workflow.mergeBranch(repoDir, "feature/x", "main")
        assertThat(result).isEqualTo(MergeResult.SUCCESS)
    }

    @Test
    fun `mergeBranch returns CONFLICT on merge conflict`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("conflict.txt").writeText("main version")
        runGit("add", "-A")
        runGit("commit", "-m", "main change")
        workflow.createBranchFrom(repoDir, "feature/conflict-source", "main")
        repoDir.resolve("conflict.txt").writeText("branch version")
        runGit("add", "-A")
        runGit("commit", "-m", "branch change")
        runGit("checkout", "main")
        val result = workflow.mergeBranch(repoDir, "feature/conflict-source", "main")
        assertThat(result).isEqualTo(MergeResult.CONFLICT)
    }

    @Test
    fun `deleteBranch deletes the branch`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranchFrom(repoDir, "feature/to-delete", "main")
        runGit("checkout", "main")
        workflow.deleteBranch(repoDir, "feature/to-delete")
        val branches = runGit("branch")
        assertThat(branches.contains("feature/to-delete")).isFalse()
    }

    @Test
    fun `deleteBranch does nothing when disabled`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.deleteBranch(repoDir, "feature/x")
    }

    @Test
    fun `commitPrefix returns fix for fix label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("fix.txt").writeText("fix")
        workflow.commitAndPush(repoDir, "ABC-1", "Fix", labels = listOf("fix"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("fix: ABC-1: Fix")
    }

    @Test
    fun `commitPrefix returns fix for bug label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("bug.txt").writeText("bug")
        workflow.commitAndPush(repoDir, "ABC-1", "Bugfix", labels = listOf("bug"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("fix: ABC-1: Bugfix")
    }

    @Test
    fun `commitPrefix returns docs for docs label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("docs.txt").writeText("docs")
        workflow.commitAndPush(repoDir, "ABC-1", "Doc update", labels = listOf("docs"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("docs: ABC-1: Doc update")
    }

    @Test
    fun `commitPrefix returns feat for enhancement label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("enhance.txt").writeText("enhance")
        workflow.commitAndPush(repoDir, "ABC-1", "New feature", labels = listOf("enhancement"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("feat: ABC-1: New feature")
    }

    @Test
    fun `commitPrefix returns test for test label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("test.txt").writeText("test")
        workflow.commitAndPush(repoDir, "ABC-1", "Add tests", labels = listOf("test"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("test: ABC-1: Add tests")
    }

    @Test
    fun `commitPrefix returns refactor for refactor label`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("refactor.txt").writeText("refactor")
        workflow.commitAndPush(repoDir, "ABC-1", "Refactor", labels = listOf("refactor"))
        val log = runGit("log", "--oneline")
        assertThat(log).contains("refactor: ABC-1: Refactor")
    }

    @Test
    fun `createBranch on non-git dir skips silently when disabled`() {
        val nonRepoDir = Files.createTempDirectory("not-a-repo-disabled-")
        Files.writeString(nonRepoDir.resolve("test.txt"), "data")
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(nonRepoDir, "ABC-1")
    }

    @Test
    fun `commitPrefix for additional label types`() {
        val config = GitConfig(enabled = true, autoCommit = true, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        val labelMappings = listOf(
            "chore" to "chore",
            "perf" to "perf",
            "style" to "style",
            "build" to "build",
            "ci" to "ci",
            "revert" to "revert",
            "documentation" to "docs"
        )
        for ((label, expected) in labelMappings) {
            repoDir.resolve("$label.txt").writeText(label)
            workflow.commitAndPush(repoDir, "ABC-1", label, labels = listOf(label))
        }
        val log = runGit("log", "--oneline")
        for ((label, expected) in labelMappings) {
            assertThat(log).contains("$expected: ABC-1: $label")
        }
    }

    @Test
    fun `commitAndPush does nothing when autoCommit disabled`() {
        val config = GitConfig(enabled = true, autoCommit = false, autoPush = false)
        val workflow = GitWorkflow(config, noopLogger())
        repoDir.resolve("no-commit.txt").writeText("data")
        workflow.commitAndPush(repoDir, "ABC-1", "No commit")
        val log = runGit("log", "--oneline")
        assertThat(log.lines().size).isEqualTo(1)
    }
}
