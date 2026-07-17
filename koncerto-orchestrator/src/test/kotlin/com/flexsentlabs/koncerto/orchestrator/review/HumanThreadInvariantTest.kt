package com.flexsentlabs.koncerto.orchestrator.review

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import org.junit.jupiter.api.Test

/**
 * Guard for INV-5 / FR-10 (Epic 21): **agents never resolve human-initiated threads.**
 *
 * Noisy bots that close humans' threads are the fastest way to destroy trust in AI review, so
 * this is enforced as a test rather than a convention. It fails if any source file introduces
 * a GitHub thread-resolve/minimize mutation. If a future feature legitimately needs to resolve
 * a thread *Koncerto itself* created, it must (a) verify the koncerto-finding marker and
 * (b) update this guard deliberately — never by accident.
 */
class HumanThreadInvariantTest {

    /** GraphQL mutations / CLI calls that would hide or resolve a review thread. */
    private val forbiddenPatterns = listOf(
        "resolveReviewThread",
        "unresolveReviewThread",
        "minimizeComment",
        "hideComment"
    )

    private fun repoRoot(): Path {
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            if (Files.exists(dir.resolve("settings.gradle.kts"))) return dir
            dir = dir.parent
        }
        error("could not locate repo root")
    }

    @Test
    fun `no source path resolves or minimizes review threads`() {
        val root = repoRoot()
        val sourceDirs = listOf("koncerto-orchestrator", "koncerto-agent", "koncerto-linear", "koncerto-dashboard")
            .map { root.resolve(it).resolve("src/main") }
            .filter { Files.isDirectory(it) }

        val violations = mutableListOf<String>()
        for (dir in sourceDirs) {
            Files.walk(dir).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                    .collect(Collectors.toList())
                for (file in files) {
                    val text = runCatching { Files.readString(file) }.getOrNull() ?: continue
                    for (pattern in forbiddenPatterns) {
                        if (text.contains(pattern)) {
                            violations.add("${root.relativize(file)} contains '$pattern'")
                        }
                    }
                }
            }
        }

        assertThat(violations).isEmpty()
    }

    @Test
    fun `koncerto-authored comments are identifiable by a finding marker`() {
        // The marker is what lets any future thread-management code tell "ours" from "theirs".
        val rendered = ReviewCommentRenderer.findingMarker("run-1-2")
        assertThat(rendered).isEqualTo("<!-- koncerto-finding:run-1-2 -->")
        assertThat(ReviewCommentRenderer.isKoncertoAuthored(rendered)).isTrue()
        assertThat(ReviewCommentRenderer.isKoncertoAuthored("Looks good to me!")).isEqualTo(false)
    }
}
