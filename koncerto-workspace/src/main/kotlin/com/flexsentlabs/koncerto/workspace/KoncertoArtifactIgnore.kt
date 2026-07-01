package com.flexsentlabs.koncerto.workspace

import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps Koncerto pipeline artifacts (trace logs, review state) out of target-project git history.
 */
object KoncertoArtifactIgnore {
    const val MARKER = "# Koncerto pipeline artifacts (auto-managed — do not commit)"

    val GITIGNORE_BLOCK = """
        |$MARKER
        |.koncerto/
        |.review-*
        |.model-exhausted*
    """.trimMargin()

    private val UNTRACK_PATHSPECS = listOf(
        ".koncerto",
        ".review-status",
        ".review-output",
        ".review-output-detailed",
        ".review-attempt",
        ".review-exhausted",
        ".review-exhausted.tmp",
        ".review-body.txt",
    )

    fun ensureGitignore(workspacePath: Path) {
        val gitignore = workspacePath.resolve(".gitignore")
        if (Files.exists(gitignore)) {
            val content = Files.readString(gitignore)
            if (content.contains(MARKER)) return
            val separator = if (content.endsWith("\n") || content.isEmpty()) "" else "\n"
            Files.writeString(gitignore, content + separator + "\n" + GITIGNORE_BLOCK + "\n")
        } else {
            Files.writeString(gitignore, GITIGNORE_BLOCK + "\n")
        }
    }

    fun untrackArtifacts(workspacePath: Path, runGit: (Path, Array<String>) -> String?) {
        if (!Files.exists(workspacePath.resolve(".git"))) return
        runGit(workspacePath, arrayOf("rm", "-r", "--cached", "--ignore-unmatch", ".koncerto"))
        UNTRACK_PATHSPECS.forEach { spec ->
            runGit(workspacePath, arrayOf("rm", "--cached", "--ignore-unmatch", spec))
        }
        runGit(workspacePath, arrayOf("rm", "--cached", "--ignore-unmatch", "--", ".model-exhausted"))
    }
}
