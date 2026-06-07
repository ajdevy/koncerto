package com.anomaly.koncerto.logging

import java.time.Instant

class StructuredLogger(private val sinks: List<LogSink>) {

    fun info(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("info", action, context, null, kvs.toMap())

    fun warn(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("warn", action, context, null, kvs.toMap())

    fun error(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("error", action, context, null, kvs.toMap())

    fun failure(
        action: String,
        context: Map<String, Any?>,
        error: Throwable,
        vararg kvs: Pair<String, Any?>
    ) = log("error", action, context, error, kvs.toMap())

    fun debug(action: String, context: Map<String, Any?>, vararg kvs: Pair<String, Any?>) =
        log("debug", action, context, null, kvs.toMap())

    private fun log(
        level: String,
        action: String,
        context: Map<String, Any?>,
        error: Throwable?,
        kvs: Map<String, Any?>
    ) {
        val parts = mutableListOf<Pair<String, String>>()
        parts += "ts" to Instant.now().toString()
        parts += "level" to level
        parts += "action" to action
        parts += "outcome" to when {
            error != null -> "failed"
            level == "warn" -> "warning"
            level == "error" -> "failed"
            else -> "completed"
        }
        context.forEach { (k, v) -> parts += k to v.toString() }
        kvs.forEach { (k, v) -> parts += k to v.toString() }
        if (error != null) {
            parts += "error" to (error.message ?: error::class.java.simpleName)
        }
        val line = parts.joinToString(" ") { (k, v) -> "$k=${quote(v)}" }
        sinks.forEach { try { it.write(line) } catch (_: Throwable) { } }
    }

    private fun quote(v: String): String {
        val needsQuote = v.any { it.isWhitespace() || it == '"' }
        return if (needsQuote) "\"" + v.replace("\"", "\\\"") + "\"" else v
    }
}
