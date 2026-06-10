package com.anomaly.koncerto.workspace

import com.anomaly.koncerto.core.config.GitConfig
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path

class GitWorkflow(
    private val config: GitConfig,
    private val logger: StructuredLogger
) {
    fun branchName(identifier: String): String = "${config.branchPrefix}$identifier"

    fun createBranch(workspacePath: Path, issueIdentifier: String) {
        if (!config.enabled) return
        if (!isGitRepo(workspacePath)) {
            logger.warn("git_not_a_repository", mapOf("path" to workspacePath.toString()))
            return
        }
        val branch = branchName(issueIdentifier)
        val created = runGitSafe(workspacePath, "checkout", "-b", branch)
        if (created == null) {
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
