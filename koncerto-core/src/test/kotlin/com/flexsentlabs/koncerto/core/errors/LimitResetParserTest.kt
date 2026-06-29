package com.flexsentlabs.koncerto.core.errors

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
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

    @Test
    fun `defaultDelayMs uses provider specific defaults`() {
        assertThat(LimitResetParser.defaultDelayMs("claude", 1000L, 2000L)).isEqualTo(1000L)
        assertThat(LimitResetParser.defaultDelayMs("codex", 1000L, 2000L)).isEqualTo(2000L)
        assertThat(LimitResetParser.defaultDelayMs("other")).isEqualTo(LimitResetParser.DEFAULT_RESUME_MS)
    }

    @Test
    fun `parseTryAgainAt parses ISO datetime format`() {
        val zone = ZoneId.systemDefault()
        val reference = LocalDateTime.of(2026, 3, 1, 10, 0)
        val nowMs = reference.atZone(zone).toInstant().toEpochMilli()
        val resume = LimitResetParser.parseTryAgainAt("try again at 2026-03-01 15:30:00", nowMs)
        assertThat(resume).isNotNull()
        assertThat(resume!!).isGreaterThan(nowMs)
    }

    @Test
    fun `resolveResumeAtMs uses custom default delays`() {
        val now = 1_000_000L
        val resume = LimitResetParser.resolveResumeAtMs(
            message = "limit hit",
            provider = "claude",
            nowMs = now,
            claudeDefaultMs = 60_000L,
            codexDefaultMs = 120_000L
        )
        assertThat(resume).isEqualTo(now + 60_000L)
    }

    @Test
    fun `parseTryAgainAt rolls forward to next year when parsed time already passed`() {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.of(2026, 12, 31, 23, 30)
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val parsed = LimitResetParser.parseTryAgainAt(
            message = "try again at Dec 30th, 2026 9:00 PM.",
            nowMs = nowMs
        )
        assertThat(parsed).isNotNull()
        assertThat(parsed!!).isGreaterThan(nowMs)
    }

    @Test
    fun `defaultDelayMs handles uppercase provider`() {
        assertThat(LimitResetParser.defaultDelayMs("CODEX", 1000L, 2000L)).isEqualTo(2000L)
    }

    @Test
    fun `resolveResumeAtMs uses current time default parameter`() {
        val before = System.currentTimeMillis()
        val resume = LimitResetParser.resolveResumeAtMs(
            message = "no parser match",
            provider = "unknown"
        )
        assertThat(resume).isGreaterThan(before)
    }

    @Test
    fun `parseTryAgainAt returns null for invalid timestamp`() {
        val parsed = LimitResetParser.parseTryAgainAt(
            message = "try again at not-a-real-date",
            nowMs = System.currentTimeMillis()
        )
        assertThat(parsed).isNull()
    }
}
