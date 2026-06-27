package com.flexsentlabs.koncerto.orchestrator

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object LimitPauseComments {
    private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a z", Locale.ENGLISH)

    fun pauseBody(
        identifier: String,
        provider: String,
        resumeAtMs: Long,
        errorSummary: String
    ): String {
        val resumeAt = formatInstant(resumeAtMs)
        return """
            |⏸️ **Agent paused — subscription limit** ($provider)
            |
            |Issue **$identifier** stays in **In Progress**. Partial work is preserved in the workspace; Koncerto will resume automatically.
            |
            |**Resume at:** $resumeAt
            |
            |**Details:** ${errorSummary.take(500)}
        """.trimMargin()
    }

    fun resumeBody(identifier: String, provider: String): String {
        return """
            |▶️ **Agent resuming** ($provider)
            |
            |Subscription limit window has passed. Re-dispatching **$identifier** (still **In Progress**).
        """.trimMargin()
    }

    private fun formatInstant(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DISPLAY_FORMAT)
}
