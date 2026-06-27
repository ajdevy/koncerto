package com.flexsentlabs.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

class LimitResetParserTest {

    @Test
    fun `uses five hour default for claude when unparseable`() {
        val now = System.currentTimeMillis()
        val resume = LimitResetParser.resolveResumeAtMs(
            message = "API Error: Rate limit reached",
            provider = "claude",
            nowMs = now
        )
        assertThat(resume).isGreaterThan(now + LimitResetParser.DEFAULT_RESUME_MS - 1000)
        assertThat(resume).isGreaterThan(now + 4 * 60 * 60 * 1000L)
    }

    @Test
    fun `uses five hour default for codex when unparseable`() {
        val now = System.currentTimeMillis()
        val resume = LimitResetParser.resolveResumeAtMs(
            message = "You've hit your usage limit",
            provider = "codex",
            nowMs = now
        )
        assertThat(resume - now).isGreaterThan(4 * 60 * 60 * 1000L)
    }

    @Test
    fun `parses codex try again at message`() {
        val zone = ZoneId.systemDefault()
        val reference = LocalDateTime.of(2026, 2, 23, 12, 0)
        val nowMs = reference.atZone(zone).toInstant().toEpochMilli()
        val message = "You've hit your usage limit. try again at Feb 23rd, 2026 9:01 PM."
        val resume = LimitResetParser.resolveResumeAtMs(message, "codex", nowMs)
        assertThat(resume).isGreaterThan(nowMs)
    }

    @Test
    fun `parseTryAgainAt returns null for missing phrase`() {
        assertThat(LimitResetParser.parseTryAgainAt("no reset here", 0L)).isNull()
    }
}
