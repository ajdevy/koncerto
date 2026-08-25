package com.flexsentlabs.koncerto.demo.recorder.api

/**
 * Parsing for the KEY=VALUE credentials file the orchestrator stages for a demo run.
 *
 * Kept separate from the recorders (which are container plumbing, excluded from coverage) so the
 * parsing a demo's authentication depends on is unit-tested rather than assumed.
 */
object RecorderEnv {

    /**
     * Parses KEY=VALUE lines, ignoring blanks and `#` comments. Only the first `=` splits, so a
     * value may contain `=` — common in tokens. Surrounding quotes are stripped, since operators
     * write both quoted and bare values.
     */
    fun parseKeyValues(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator).trim()
                if (key.isEmpty()) return@mapNotNull null
                key to unquote(line.substring(separator + 1).trim())
            }
            .toMap()

    private fun unquote(value: String): String =
        if (value.length >= 2 && (value.startsWith("\"") && value.endsWith("\"") ||
                value.startsWith("'") && value.endsWith("'"))
        ) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
