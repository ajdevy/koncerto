package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

    private fun runGitIn(dir: Path, vararg args: String): String {
        val pb = ProcessBuilder(listOf("git") + args.toList())
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val out = pb.inputStream.bufferedReader().readText()
        pb.waitFor()
        return out.trim()
    }

    private fun createBareRemoteWithMain(): Path {
        val bareDir = Files.createTempDirectory("bare-origin-")
        ProcessBuilder("git", "init", "--bare", "--initial-branch=main")
            .directory(bareDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        val seedDir = Files.createTempDirectory("seed-repo-")
        ProcessBuilder("git", "init", "--initial-branch=main")
            .directory(seedDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        runGitIn(seedDir, "config", "user.email", "test@test.com")
        runGitIn(seedDir, "config", "user.name", "Test")
        seedDir.resolve("README.md").writeText("# seed")
        runGitIn(seedDir, "add", "-A")
        runGitIn(seedDir, "commit", "-m", "initial")
        runGitIn(seedDir, "remote", "add", "origin", bareDir.toString())
        runGitIn(seedDir, "push", "-u", "origin", "main")
        return bareDir
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
    fun `not a git repo auto-inits in createBranch`() {
        val nonRepoDir = Files.createTempDirectory("not-a-repo-")
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(nonRepoDir, "ABC-1")

        val isRepo = nonRepoDir.resolve(".git").toFile().exists()
        assertThat(isRepo).isEqualTo(true)
        val hasWarning = logs.any { it.contains("git_not_a_repository") }
        assertThat(hasWarning).isEqualTo(false)
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
    fun `not a git repo auto-inits on createBranch call`() {
        val nonRepoDir = Files.createTempDirectory("not-a-repo-")
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(nonRepoDir, "ABC-1")
        val isRepo = nonRepoDir.resolve(".git").toFile().exists()
        assertThat(isRepo).isEqualTo(true)
        val hasWarning = logs.any { it.contains("git_not_a_repository") }
        assertThat(hasWarning).isEqualTo(false)
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

    @Test
    fun `remoteBranchExists returns false when git disabled`() {
        val config = GitConfig(enabled = false)
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.remoteBranchExists("feature/ABC-1", repoDir)).isFalse()
    }

    @Test
    fun `remoteBranchExists returns true when ls-remote finds branch`() {
        val bareDir = Files.createTempDirectory("bare-remote-")
        ProcessBuilder("git", "init", "--bare")
            .directory(bareDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        runGit("remote", "add", "origin", bareDir.toString())
        runGit("checkout", "-b", "feature/ABC-1")
        runGit("push", "-u", "origin", "feature/ABC-1")

        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.remoteBranchExists("feature/ABC-1", repoDir)).isTrue()
    }

    @Test
    fun `remoteBranchExists returns false when ls-remote empty`() {
        val bareDir = Files.createTempDirectory("bare-remote-")
        ProcessBuilder("git", "init", "--bare")
            .directory(bareDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        runGit("remote", "add", "origin", bareDir.toString())

        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.remoteBranchExists("feature/MISSING", repoDir)).isFalse()
    }

    @Test
    fun `remoteBranchExists returns false when git command fails`() {
        runGit("remote", "add", "origin", "/nonexistent/koncerto-remote.git")

        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        assertThat(workflow.remoteBranchExists("feature/ABC-1", repoDir)).isFalse()
    }

    @Test
    fun `createBranch initializes new repo from remote base when remoteUrl set`() {
        val bareDir = createBareRemoteWithMain()
        val newDir = Files.createTempDirectory("new-remote-ws-")
        val config = GitConfig(enabled = true, remoteUrl = bareDir.toString(), prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(newDir, "ABC-1")

        val branch = runGitIn(newDir, "rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/ABC-1")
        val remote = runGitIn(newDir, "remote", "get-url", "origin")
        assertThat(remote).isEqualTo(bareDir.toString())
    }

    @Test
    fun `createBranch on existing repo checks out from origin base when remoteUrl set`() {
        val bareDir = createBareRemoteWithMain()
        runGit("remote", "add", "origin", bareDir.toString())
        runGit("fetch", "origin", "main")
        runGit("branch", "-f", "main", "origin/main")

        val config = GitConfig(enabled = true, remoteUrl = bareDir.toString(), prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(repoDir, "REMOTE-1")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/REMOTE-1")
    }

    @Test
    fun `createBranch removes stale untracked artifacts before checkout`() {
        repoDir.resolve(".koncerto").toFile().mkdirs()
        repoDir.resolve(".koncerto/dispatch-trace-2026-06-30.jsonl").writeText("stale")

        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(repoDir, "CLEAN-1")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/CLEAN-1")
        assertThat(repoDir.resolve(".koncerto/dispatch-trace-2026-06-30.jsonl").toFile().exists()).isFalse()
    }

    @Test
    fun `createBranch resets existing local branch to matching remote branch`() {
        val bareDir = createBareRemoteWithMain()
        val seedDir = Files.createTempDirectory("seed-feature-")
        ProcessBuilder("git", "init", "--initial-branch=main")
            .directory(seedDir.toFile())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        runGitIn(seedDir, "config", "user.email", "test@test.com")
        runGitIn(seedDir, "config", "user.name", "Test")
        seedDir.resolve("README.md").writeText("# seed")
        runGitIn(seedDir, "add", "-A")
        runGitIn(seedDir, "commit", "-m", "initial")
        runGitIn(seedDir, "remote", "add", "origin", bareDir.toString())
        runGitIn(seedDir, "push", "-u", "origin", "main")
        runGitIn(seedDir, "checkout", "-b", "feature/EXISTING-REMOTE")
        seedDir.resolve("remote.txt").writeText("remote")
        runGitIn(seedDir, "add", "-A")
        runGitIn(seedDir, "commit", "-m", "remote branch")
        val remoteHead = runGitIn(seedDir, "rev-parse", "HEAD")
        runGitIn(seedDir, "push", "-u", "origin", "feature/EXISTING-REMOTE")

        runGit("remote", "add", "origin", bareDir.toString())
        runGit("fetch", "origin", "main", "feature/EXISTING-REMOTE")
        runGit("checkout", "-b", "feature/EXISTING-REMOTE")
        repoDir.resolve("local.txt").writeText("local-only")
        runGit("add", "-A")
        runGit("commit", "-m", "local branch")
        runGit("checkout", "main")

        val config = GitConfig(enabled = true, remoteUrl = bareDir.toString(), prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(repoDir, "EXISTING-REMOTE")

        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        val head = runGit("rev-parse", "HEAD")
        assertThat(branch).isEqualTo("feature/EXISTING-REMOTE")
        assertThat(head).isEqualTo(remoteHead)
    }

    @Test
    fun `createBranch skips adding origin when remote already exists`() {
        val bareDir = createBareRemoteWithMain()
        runGit("remote", "add", "origin", bareDir.toString())
        val config = GitConfig(enabled = true, remoteUrl = bareDir.toString(), prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(repoDir, "EXISTING-ORIGIN")

        assertThat(logs.any { it.contains("origin_remote_exists") }).isTrue()
        val branch = runGit("rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/EXISTING-ORIGIN")
    }

    @Test
    fun `createBranch falls back to local init when remote fetch fails`() {
        val newDir = Files.createTempDirectory("bad-remote-ws-")
        val config = GitConfig(
            enabled = true,
            remoteUrl = "/nonexistent/koncerto-bare.git",
            prBase = "main"
        )
        val workflow = GitWorkflow(config, noopLogger())

        workflow.createBranch(newDir, "FALLBACK-1")

        val branch = runGitIn(newDir, "rev-parse", "--abbrev-ref", "HEAD")
        assertThat(branch).isEqualTo("feature/FALLBACK-1")
        assertThat(newDir.resolve("README.md").toFile().exists()).isTrue()
    }

    @Test
    fun `subtaskBranchName follows convention`() {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        assertThat(workflow.subtaskBranchName("ABC-1", "task-1")).isEqualTo("subtask/ABC-1/task-1")
    }

    @Test
    fun `setupOriginRemote skips when remote url blank`() {
        val dir = Files.createTempDirectory("origin-blank-ws-")
        runGitIn(dir, "init", "--initial-branch=main")
        val config = GitConfig(enabled = true, remoteUrl = "", prBase = "main")
        val workflow = GitWorkflow(config, noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
        method.isAccessible = true
        method.invoke(workflow, dir)
        val remotes = runGitIn(dir, "remote")
        assertThat(remotes.isBlank()).isTrue()
    }

    @Test
    fun `setupOriginRemote adds origin without token`() {
        val dir = Files.createTempDirectory("origin-add-ws-")
        val config = GitConfig(
            enabled = true,
            remoteUrl = "https://github.com/acme/widget.git",
            prBase = "main"
        )
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(dir, "ORIGIN-1")
        val remote = runGitIn(dir, "remote", "get-url", "origin")
        assertThat(remote).isEqualTo("https://github.com/acme/widget.git")
    }

    @Test
    fun `setupOriginRemote updates origin when configured url differs`() {
        val dir = Files.createTempDirectory("origin-exists-ws-")
        runGitIn(dir, "init", "--initial-branch=main")
        runGitIn(dir, "remote", "add", "origin", "https://github.com/existing/repo.git")
        val config = GitConfig(
            enabled = true,
            remoteUrl = "https://github.com/acme/widget.git",
            prBase = "main"
        )
        val workflow = GitWorkflow(config, noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
        method.isAccessible = true
        method.invoke(workflow, dir)
        val remote = runGitIn(dir, "remote", "get-url", "origin")
        assertThat(remote).isEqualTo("https://github.com/acme/widget.git")
        assertThat(logs.any { it.contains("origin_remote_updated") }).isTrue()
    }

    @Test
    fun `mergeBranch returns success on clean merge`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        runGit("checkout", "-b", "feature/clean")
        repoDir.resolve("clean.txt").writeText("feature")
        runGit("add", "-A")
        runGit("commit", "-m", "feature commit")
        runGit("checkout", "main")
        val result = workflow.mergeBranch(repoDir, "feature/clean", "main")
        assertThat(result).isEqualTo(MergeResult.SUCCESS)
        assertThat(repoDir.resolve("clean.txt").readText()).isEqualTo("feature")
    }

    @Test
    fun `mergeBranch returns success when git merge fails without captured output`() {
        val config = GitConfig(enabled = true)
        val workflow = GitWorkflow(config, noopLogger())
        runGit("checkout", "-b", "feature/conflict")
        repoDir.resolve("conflict.txt").writeText("feature version")
        runGit("add", "-A")
        runGit("commit", "-m", "feature side")
        runGit("checkout", "main")
        repoDir.resolve("conflict.txt").writeText("main version")
        runGit("add", "-A")
        runGit("commit", "-m", "main side")
        val result = workflow.mergeBranch(repoDir, "feature/conflict", "main")
        assertThat(result).isEqualTo(MergeResult.SUCCESS)
    }

    @Test
    fun `commitAndPush force pushes when initial push rejected`() {
        val bareDir = createBareRemoteWithMain()
        runGit("remote", "add", "origin", bareDir.toString())
        runGit("fetch", "origin", "main")
        runGit("branch", "-f", "main", "origin/main")
        runGit("checkout", "-b", "feature/push")
        repoDir.resolve("push.txt").writeText("local")
        runGit("add", "-A")
        runGit("commit", "-m", "local commit")
        runGit("push", "-u", "origin", "feature/push")
        repoDir.resolve("push.txt").writeText("diverged")
        runGit("add", "-A")
        runGit("commit", "-m", "diverged commit")
        val config = GitConfig(enabled = true, autoCommit = false, autoPush = true, remoteUrl = bareDir.toString())
        val workflow = GitWorkflow(config, noopLogger())
        workflow.commitAndPush(repoDir, "PUSH-1", "Force push test")
        val log = runGit("log", "--oneline", "-1")
        assertThat(log.contains("diverged")).isTrue()
    }

    @Test
    fun `runCmdSafe returns output when command succeeds`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init")
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod(
            "runCmdSafe", String::class.java, Path::class.java, Array<String>::class.java
        )
        method.isAccessible = true
        val output = method.invoke(workflow, "git", tmpDir, arrayOf("rev-parse", "--is-inside-work-tree")) as String?
        assertThat(output).isEqualTo("true")
    }

    @Test
    fun `runCmdSafe returns null for non-zero exit`(@TempDir tmpDir: Path) {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod(
            "runCmdSafe", String::class.java, Path::class.java, Array<String>::class.java
        )
        method.isAccessible = true
        val output = method.invoke(workflow, "false", tmpDir, emptyArray<String>()) as String?
        assertThat(output).isNull()
    }

    @Test
    fun `runCmdSafe returns null when command fails`() {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod(
            "runCmdSafe", String::class.java, Path::class.java, Array<String>::class.java
        )
        method.isAccessible = true
        val result = method.invoke(workflow, "git", repoDir, arrayOf("not-a-real-subcommand"))
        assertThat(result).isNull()
    }

    @Test
    fun `setupOriginRemote adds origin when missing`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init")
        runGitIn(tmpDir, "config", "user.email", "t@example.com")
        runGitIn(tmpDir, "config", "user.name", "Test")
        val workflow = GitWorkflow(GitConfig(enabled = true, remoteUrl = "https://github.com/acme/repo.git"), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
        method.isAccessible = true
        method.invoke(workflow, tmpDir)
        val remote = runGitIn(tmpDir, "remote", "get-url", "origin")
        assertThat(remote.trim()).isEqualTo("https://github.com/acme/repo.git")
    }

    @Test
    fun `isGitRepo returns false for non-git directory`(@TempDir tmpDir: Path) {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("isGitRepo", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, tmpDir) as Boolean
        assertThat(result).isFalse()
    }

    @Test
    fun `isGitRepo returns true for git directory`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init")
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("isGitRepo", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, tmpDir) as Boolean
        assertThat(result).isTrue()
    }

    @Test
    fun `companion testGhTokenOverride roundtrips`() {
        GitWorkflow.testGhTokenOverride = "roundtrip-token"
        assertThat(GitWorkflow.testGhTokenOverride).isEqualTo("roundtrip-token")
        GitWorkflow.testGhTokenOverride = null
    }

    @Test
    fun `isGitRepo returns false when only parent directory is a git repo`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init", "--initial-branch=main")
        val workspace = tmpDir.resolve("FLE-52")
        Files.createDirectories(workspace)
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("isGitRepo", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, workspace) as Boolean
        assertThat(result).isFalse()
    }

    @Test
    fun `createBranch initializes isolated repo inside parent git checkout`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init", "--initial-branch=main")
        runGitIn(tmpDir, "config", "user.email", "test@test.com")
        runGitIn(tmpDir, "config", "user.name", "Test")
        tmpDir.resolve("README.md").writeText("# parent")
        runGitIn(tmpDir, "add", "-A")
        runGitIn(tmpDir, "commit", "-m", "parent initial")

        val workspace = tmpDir.resolve("FLE-52")
        Files.createDirectories(workspace)
        val config = GitConfig(enabled = true, remoteUrl = "https://github.com/acme/target.git")
        val workflow = GitWorkflow(config, noopLogger())
        workflow.createBranch(workspace, "FLE-52")

        assertThat(workspace.resolve(".git").toFile().exists()).isTrue()
        assertThat(runGitIn(tmpDir, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main")
        assertThat(runGitIn(workspace, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("feature/FLE-52")
        assertThat(runGitIn(workspace, "remote", "get-url", "origin"))
            .isEqualTo("https://github.com/acme/target.git")
    }

    @Test
    fun `isGitRepo returns false when path is a file not directory`(@TempDir tmpDir: Path) {
        val file = tmpDir.resolve("not-a-directory.txt")
        Files.writeString(file, "contents")
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod("isGitRepo", Path::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, file) as Boolean
        assertThat(result).isFalse()
    }

    @Test
    fun `runCmdSafe logs and returns null for failing git command in repo`() {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod(
            "runCmdSafe", String::class.java, Path::class.java, Array<String>::class.java
        )
        method.isAccessible = true
        val result = method.invoke(workflow, "git", repoDir, arrayOf("cat-file", "-p", "not-a-valid-object"))
        assertThat(result).isNull()
    }

    @Test
    fun `setupOriginRemote injects test GH token into remote url`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init")
        GitWorkflow.testGhTokenOverride = "coverage-test-token"
        try {
            val workflow = GitWorkflow(
                GitConfig(enabled = true, remoteUrl = "https://github.com/acme/repo.git"),
                noopLogger()
            )
            val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
            method.isAccessible = true
            method.invoke(workflow, tmpDir)
            val remote = runGitIn(tmpDir, "remote", "get-url", "origin")
            assertThat(remote).contains("x-access-token:coverage-test-token@")
        } finally {
            GitWorkflow.testGhTokenOverride = null
        }
    }

    @Test
    fun `setupOriginRemote logs origin_remote_added for new remote`(@TempDir tmpDir: Path) {
        runGitIn(tmpDir, "init")
        val sink = mutableListOf<String>()
        val workflow = GitWorkflow(
            GitConfig(enabled = true, remoteUrl = "https://github.com/acme/new-repo.git"),
            StructuredLogger(listOf(object : com.flexsentlabs.koncerto.logging.LogSink {
                override fun write(line: String) { sink.add(line) }
            }))
        )
        val method = GitWorkflow::class.java.getDeclaredMethod("setupOriginRemote", Path::class.java)
        method.isAccessible = true
        method.invoke(workflow, tmpDir)
        assertThat(sink.any { it.contains("origin_remote_added") }).isTrue()
    }

    @Test
    fun `commitAndPush no-ops when not a git repo`(@TempDir tmpDir: Path) {
        val workflow = GitWorkflow(GitConfig(enabled = true, autoCommit = true, autoPush = true), noopLogger())
        workflow.commitAndPush(tmpDir, "NOGIT-1", "title")
    }

    @Test
    fun `runCmdSafe returns null when process throws`(@TempDir tmpDir: Path) {
        val workflow = GitWorkflow(GitConfig(enabled = true), noopLogger())
        val method = GitWorkflow::class.java.getDeclaredMethod(
            "runCmdSafe", String::class.java, Path::class.java, Array<String>::class.java
        )
        method.isAccessible = true
        val bogusDir = tmpDir.resolve("missing/nested/path")
        val result = method.invoke(workflow, "git", bogusDir, arrayOf("status"))
        assertThat(result).isNull()
    }
}
