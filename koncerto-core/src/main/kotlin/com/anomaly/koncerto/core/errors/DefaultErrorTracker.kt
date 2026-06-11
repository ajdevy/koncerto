package com.anomaly.koncerto.core.errors

class DefaultErrorTracker : ErrorTracker {
    private val errors = java.util.concurrent.ConcurrentHashMap<String, ErrorRecord>()

    override fun recordError(key: String, error: String, category: String) {
        errors.compute(key) { _, existing ->
            if (existing != null) {
                existing.copy(
                    count = existing.count + 1,
                    lastSeen = System.currentTimeMillis()
                )
            } else {
                ErrorRecord(key, error, 1, System.currentTimeMillis(), System.currentTimeMillis(), category)
            }
        }
    }

    override fun getErrorCount(key: String): Int = errors[key]?.count ?: 0

    override fun getErrorsByCategory(category: String): List<ErrorRecord> =
        errors.values.filter { it.category == category }.toList()

    override fun getAllErrors(): List<ErrorRecord> = errors.values.toList()

    override fun resetCounter(key: String) { errors.remove(key) }

    override fun getTotalErrorCount(): Int = errors.values.sumOf { it.count }

    override fun getTopErrors(limit: Int): List<ErrorRecord> =
        errors.values.sortedByDescending { it.count }.take(limit)
}
