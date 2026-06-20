package com.flexsentlabs.koncerto.workspace

import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path

sealed class MergeResult {
    object SUCCESS : MergeResult()
    object CONFLICT : MergeResult()
}

open class GitWorkflow(
    private val config: GitConfig,
    private val logger: StructuredLogger
) {
    fun branchName(identifier: String): String = "${config.branchPrefix}$identifier"

    fun remoteBranchExists(branchName: String, workspacePath: Path): Boolean {
        if (!config.enabled) return false
        val ref = runCmdSafe("git", workspacePath, "ls-remote", "--heads", "origin", branchName)
        return !ref.isNullOrBlank()
    }

    open fun subtaskBranchName(issueIdentifier: String, subtaskId: String): String =
        "subtask/$issueIdentifier/$subtaskId"

    open fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {
        if (!config.enabled) return
        runGitSafe(workspacePath, "checkout", sourceBranch)
        runGitSafe(workspacePath, "checkout", "-b", branchName)
        logger.info("branch_created", mapOf(
            "branch" to branchName,
            "source" to sourceBranch,
            "workspace" to workspacePath.toString()
        ))
    }

    open fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult {
        if (!config.enabled) return MergeResult.SUCCESS
        runGitSafe(workspacePath, "checkout", targetBranch)
        val output = runGitSafe(workspacePath, "merge", sourceBranch) ?: ""
        return if (output.contains("CONFLICT", ignoreCase = true)) {
            MergeResult.CONFLICT
        } else {
            MergeResult.SUCCESS
        }
    }

    open fun deleteBranch(workspacePath: Path, branchName: String) {
        if (!config.enabled) return
        runGitSafe(workspacePath, "branch", "-D", branchName)
        logger.info("branch_deleted", mapOf("branch" to branchName))
    }

    fun createBranch(workspacePath: Path, issueIdentifier: String) {
        if (!config.enabled) return
        if (!isGitRepo(workspacePath)) {
            logger.info("git_init", mapOf("path" to workspacePath.toString()))
            runGitSafe(workspacePath, "init", "--initial-branch=main")
            runGitSafe(workspacePath, "config", "user.email", "agent@koncerto.dev")
            runGitSafe(workspacePath, "config", "user.name", "Koncerto Agent")
            val readme = workspacePath.resolve("README.md")
            if (!readme.toFile().exists()) {
                readme.toFile().writeText("# ${workspacePath.fileName}\n")
            }
            runGitSafe(workspacePath, "add", "-A")
            runGitSafe(workspacePath, "commit", "--allow-empty", "-m", "initial")
        }
        val branch = branchName(issueIdentifier)
        val branchExists = runGitSafe(workspacePath, "rev-parse", "--verify", branch) != null
        val created = if (branchExists) {
            runGitSafe(workspacePath, "checkout", branch)
        } else {
            runGitSafe(workspacePath, "checkout", "-b", branch)
        }
        if (created == null && !branchExists) {
            runGitSafe(workspacePath, "checkout", branch)
        }
    }

    fun commitAndPush(workspacePath: Path, issueIdentifier: String, title: String, labels: List<String> = emptyList()) {
        if (!config.enabled) return
        if (!isGitRepo(workspacePath)) return
        val branch = branchName(issueIdentifier)
        if (config.autoCommit) {
            val prefix = commitPrefix(labels)
            runGitSafe(workspacePath, "add", "-A")
            runGitSafe(workspacePath, "commit", "--allow-empty", "-m", "$prefix: $issueIdentifier: $title")
        }
        if (config.autoPush) {
            runGitSafe(workspacePath, "push", "-u", "origin", branch)
        }
    }

    fun createPullRequest(workspacePath: Path, issueIdentifier: String, title: String, description: String?): String? {
        if (!config.enabled || !config.createPr) return null
        val branch = branchName(issueIdentifier)
        val body = description?.take(5000) ?: ""
        return runGhSafe(workspacePath, "pr", "create",
            "--base", config.prBase,
            "--head", branch,
            "--title", "$issueIdentifier: $title",
            "--body", body
        )
    }

    private fun isGitRepo(path: Path): Boolean {
        return try {
            val proc = ProcessBuilder("git", "rev-parse", "--git-dir")
                .directory(path.toFile())
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun runGitSafe(workspacePath: Path, vararg args: String): String? =
        runCmdSafe("git", workspacePath, *args)

    private fun runGhSafe(workspacePath: Path, vararg args: String): String? =
        runCmdSafe("gh", workspacePath, *args)

    private fun commitPrefix(labels: List<String>): String {
        val label = labels.firstOrNull()?.trim()?.lowercase()
        if (label == null) return "feat"
        return when (label) {
            "fix", "bug" -> "fix"
            "docs", "documentation" -> "docs"
            "refactor" -> "refactor"
            "test", "testing" -> "test"
            "chore" -> "chore"
            "perf", "performance" -> "perf"
            "style" -> "style"
            "build" -> "build"
            "ci" -> "ci"
            "revert" -> "revert"
            else -> "feat"
        }
    }

    private fun runCmdSafe(cmd: String, workspacePath: Path, vararg args: String): String? {
        return try {
            val fullCmd = listOf(cmd) + args.toList()
            val proc = ProcessBuilder(fullCmd)
                .directory(workspacePath.toFile())
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit == 0) {
                output.trim()
            } else {
                logger.warn("${cmd}_exit_$exit", mapOf(
                    "path" to workspacePath.toString(),
                    "output" to output.take(500)
                ))
                null
            }
        } catch (e: Exception) {
            logger.warn("${cmd}_failed", mapOf(
                "path" to workspacePath.toString(),
                "error" to (e.message ?: "unknown")
            ))
            null
        }
    }
}
