package com.flexsentlabs.koncerto.core.errors

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object LimitResetParser {
    const val DEFAULT_RESUME_MS: Long = 5 * 60 * 60 * 1000L

    private val TRY_AGAIN_AT = Regex("""try again at\s+(.+?)(?:\.|$)""", RegexOption.IGNORE_CASE)

    private val RESET_FORMATTERS: List<DateTimeFormatter> = listOf(
        DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
    )

    fun resolveResumeAtMs(
        message: String,
        provider: String,
        nowMs: Long = System.currentTimeMillis(),
        claudeDefaultMs: Long = DEFAULT_RESUME_MS,
        codexDefaultMs: Long = DEFAULT_RESUME_MS
    ): Long {
        parseTryAgainAt(message, nowMs)?.let { return it }
        return nowMs + defaultDelayMs(provider, claudeDefaultMs, codexDefaultMs)
    }

    fun defaultDelayMs(
        provider: String,
        claudeDefaultMs: Long = DEFAULT_RESUME_MS,
        codexDefaultMs: Long = DEFAULT_RESUME_MS
    ): Long = when (provider.lowercase()) {
        "claude" -> claudeDefaultMs
        "codex" -> codexDefaultMs
        else -> DEFAULT_RESUME_MS
    }

    internal fun parseTryAgainAt(message: String, nowMs: Long): Long? {
        val raw = TRY_AGAIN_AT.find(message)?.groupValues?.getOrNull(1)?.trim() ?: return null
        val zone = ZoneId.systemDefault()
        val reference = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        for (formatter in RESET_FORMATTERS) {
            try {
                val parsed = LocalDateTime.parse(sanitizeDaySuffix(raw), formatter)
                var candidate = parsed.atZone(zone).toInstant().toEpochMilli()
                if (candidate <= nowMs && parsed.isBefore(reference)) {
                    candidate = parsed.plusYears(1).atZone(zone).toInstant().toEpochMilli()
                }
                if (candidate > nowMs) return candidate
            } catch (_: Exception) {
                // try next formatter
            }
        }
        return null
    }

    /** Normalizes "23rd" → "23" for formatters that use numeric day-of-month. */
    private fun sanitizeDaySuffix(raw: String): String =
        raw.replace(Regex("""(\d+)(st|nd|rd|th)""", RegexOption.IGNORE_CASE), "$1")
}
