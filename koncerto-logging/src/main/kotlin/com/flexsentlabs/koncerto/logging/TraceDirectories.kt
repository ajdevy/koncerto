package com.flexsentlabs.koncerto.logging

import java.nio.file.Path

object TraceDirectories {
    private const val LOGS_ROOT_ENV = "KONCERTO_LOGS_ROOT"

    fun resolve(category: String, key: String, fallback: Path): Path {
        val logsRoot = System.getenv(LOGS_ROOT_ENV)?.trim().orEmpty()
        if (logsRoot.isBlank()) return fallback
        return Path.of(logsRoot)
            .resolve("traces")
            .resolve(sanitize(category))
            .resolve(sanitize(key))
    }

    private fun sanitize(value: String): String = buildString(value.length) {
        value.forEach { ch ->
            append(
                when {
                    ch.isLetterOrDigit() || ch == '.' || ch == '_' || ch == '-' -> ch
                    else -> '_'
                }
            )
        }
    }
}
