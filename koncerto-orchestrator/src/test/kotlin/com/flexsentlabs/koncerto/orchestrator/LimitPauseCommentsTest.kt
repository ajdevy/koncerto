package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class LimitPauseCommentsTest {

    @Test
    fun `pause body mentions in progress and resume time`() {
        val body = LimitPauseComments.pauseBody(
            identifier = "FLE-51",
            provider = "codex",
            resumeAtMs = System.currentTimeMillis() + 3_600_000,
            errorSummary = "You've hit your usage limit"
        )
        assertThat(body).contains("FLE-51")
        assertThat(body).contains("In Progress")
        assertThat(body).contains("codex")
        assertThat(body.contains("subscription limit", ignoreCase = true)).isTrue()
    }

    @Test
    fun `resume body mentions resuming`() {
        val body = LimitPauseComments.resumeBody("FLE-51", "claude")
        assertThat(body).contains("FLE-51")
        assertThat(body).contains("claude")
        assertThat(body.contains("resuming", ignoreCase = true)).isTrue()
        assertThat(body).contains("In Progress")
    }
}
