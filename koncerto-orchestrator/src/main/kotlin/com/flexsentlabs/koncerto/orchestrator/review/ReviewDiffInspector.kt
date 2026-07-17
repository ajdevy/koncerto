package com.flexsentlabs.koncerto.orchestrator.review

import java.nio.file.Path

/** Result of a `git process` invocation. Mirrors the orchestrator's GhProcessResult seam. */
data class ProcResult(val exitCode: Int, val output: String)

typealias ProcRunner = (command: List<String>, workDir: Path) -> ProcResult

/** The changed-file surface of a review, used by eligibility + routing (Epic 19). */
data class ReviewDiff(
    val changedFiles: List<String>,
    val totalLinesChanged: Int,
    val commitSha: String?
)

/**
 * Reads the diff surface from a workspace via git. Isolated behind [ProcRunner] so routing
 * and eligibility can be exercised deterministically in tests without a real repo.
 */
class ReviewDiffInspector(private val runner: ProcRunner = defaultRunner) {

    fun inspect(workspacePath: Path, baseRef: String = "HEAD~1"): ReviewDiff {
        val names = runner(listOf("git", "diff", "--name-only", baseRef), workspacePath)
        val files = if (names.exitCode == 0) {
            names.output.lines().map { it.trim() }.filter { it.isNotBlank() }
        } else emptyList()

        val numstat = runner(listOf("git", "diff", "--numstat", baseRef), workspacePath)
        val totalLines = if (numstat.exitCode == 0) parseNumstat(numstat.output) else 0

        val sha = runner(listOf("git", "rev-parse", "HEAD"), workspacePath)
            .takeIf { it.exitCode == 0 }?.output?.trim()?.take(40)

        return ReviewDiff(files, totalLines, sha)
    }

    private fun parseNumstat(output: String): Int =
        output.lines().sumOf { line ->
            val cols = line.trim().split('\t', ' ').filter { it.isNotBlank() }
            if (cols.size < 2) return@sumOf 0
            val added = cols[0].toIntOrNull() ?: 0   // "-" for binary files → 0
            val deleted = cols[1].toIntOrNull() ?: 0
            added + deleted
        }

    companion object {
        val defaultRunner: ProcRunner = { command, workDir ->
            val proc = ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            ProcResult(proc.exitValue(), out)
        }
    }
}
