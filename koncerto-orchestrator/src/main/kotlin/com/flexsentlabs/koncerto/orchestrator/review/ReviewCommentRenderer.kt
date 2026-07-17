package com.flexsentlabs.koncerto.orchestrator.review

import com.flexsentlabs.koncerto.core.review.ReviewFinding
import com.flexsentlabs.koncerto.core.review.Severity

/**
 * Renders published findings for PR publication (Epics 21/22).
 *
 * Two invariants live here:
 *  - **One comment per one issue found** — each published finding gets its own entry, and
 *    dropped findings never appear (they were filtered by the publication gate).
 *  - **Attribution** — every entry carries a `koncerto-finding` marker so later feedback can
 *    be attributed back to the finding, and so thread-management code can always distinguish
 *    a Koncerto-authored comment from a human's (INV-5).
 */
object ReviewCommentRenderer {

    private val MARKER_REGEX = Regex("""<!--\s*koncerto-finding:([^\s>]+)\s*-->""")

    fun findingMarker(findingId: String): String = "<!-- koncerto-finding:$findingId -->"

    /** True when the text was authored by Koncerto (carries a finding marker). */
    fun isKoncertoAuthored(text: String): Boolean = MARKER_REGEX.containsMatchIn(text)

    fun findingIdOf(text: String): String? = MARKER_REGEX.find(text)?.groupValues?.get(1)

    /** A single finding rendered as one standalone PR comment body. */
    fun renderFinding(runId: String, finding: ReviewFinding): String = buildString {
        append(findingMarker("$runId-${finding.seq}"))
        append('\n')
        append(severityIcon(finding.severity))
        append(" **")
        append(finding.category)
        append("**")
        finding.file?.let { file ->
            append(" — `")
            append(file)
            finding.line?.let { append(":").append(it) }
            append('`')
        }
        append("\n\n")
        append(finding.description.trim())
        finding.expectedAction?.takeIf { it.isNotBlank() }?.let {
            append("\n\n**Fix:** ").append(it.trim())
        }
        finding.evidence?.takeIf { it.isNotBlank() }?.let {
            append("\n\n<sub>Evidence: ").append(it.trim()).append("</sub>")
        }
        finding.confidence?.let {
            append("\n<sub>confidence ").append(String.format("%.2f", it)).append("</sub>")
        }
    }

    /**
     * A summary block appended to the main review comment. Only published findings appear;
     * the counts intentionally live in one place only (the review verdict line).
     */
    fun renderSummary(runId: String, published: List<ReviewFinding>): String {
        if (published.isEmpty()) return ""
        return buildString {
            append("\n\n<details><summary>🔎 Findings (")
            append(published.size)
            append(")</summary>\n\n")
            published.forEach { f ->
                append("- ")
                append(findingMarker("$runId-${f.seq}"))
                append(' ')
                append(severityIcon(f.severity))
                f.file?.let { file ->
                    append(" `").append(file)
                    f.line?.let { append(":").append(it) }
                    append('`')
                }
                append(" — ").append(f.description.trim())
                append('\n')
            }
            append("\n</details>")
        }
    }

    private fun severityIcon(severity: Severity): String = when (severity) {
        Severity.CRITICAL -> "🔴"
        Severity.WARNING -> "🟡"
        Severity.SUGGESTION -> "🔵"
    }
}
