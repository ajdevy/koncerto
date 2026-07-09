package com.flexsentlabs.koncerto.deploy

import java.nio.file.Files
import java.nio.file.Paths

/**
 * Loads a per-project secrets file (`KEY=VALUE` lines) into a map, and provides masking so secret
 * values never appear in full in logs, traces, or comments.
 *
 * Format: one `KEY=VALUE` per line; blank lines and `#` comments ignored; surrounding single/double
 * quotes on the value are stripped; a leading `export ` is tolerated. Entries with an empty value
 * are dropped (an empty value provides nothing).
 */
object SecretsFile {

    fun load(path: String?): Map<String, String> {
        if (path.isNullOrBlank()) return emptyMap()
        val file = Paths.get(path)
        if (!Files.exists(file)) return emptyMap()
        val out = linkedMapOf<String, String>()
        for (raw in runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())) {
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim()
            if (key.isEmpty()) continue
            val value = line.substring(eq + 1).trim().trim('"', '\'')
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /**
     * Masks a secret value for safe logging. Only reveals a short prefix when the value is long
     * enough that the prefix is a small fraction of it; anything under 8 chars is hidden entirely
     * (otherwise a 5-char secret would be shown in full with just an ellipsis appended).
     */
    fun mask(value: String): String = when {
        value.length < 8 -> "…"
        else -> value.take(4) + "…"
    }
}
