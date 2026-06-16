package com.flexsentlabs.koncerto.core.errors

data class ErrorRecord(
    val key: String,
    val error: String,
    val count: Int,
    val firstSeen: Long,
    val lastSeen: Long,
    val category: String = "unknown"
)

interface ErrorTracker {
    fun recordError(key: String, error: String, category: String = "unknown")
    fun getErrorCount(key: String): Int
    fun getErrorsByCategory(category: String): List<ErrorRecord>
    fun getAllErrors(): List<ErrorRecord>
    fun resetCounter(key: String)
    fun getTotalErrorCount(): Int
    fun getTopErrors(limit: Int = 10): List<ErrorRecord>
}
