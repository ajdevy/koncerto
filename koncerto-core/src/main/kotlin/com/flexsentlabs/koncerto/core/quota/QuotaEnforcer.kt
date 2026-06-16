package com.flexsentlabs.koncerto.core.quota

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class QuotaEnforcer {

    private val activeCounts = ConcurrentHashMap<String, AtomicInteger>()

    fun tryAcquire(projectSlug: String, config: QuotaConfig): Boolean {
        if (config.isUnlimited()) return true
        val counter = activeCounts.computeIfAbsent(projectSlug) { AtomicInteger(0) }
        while (true) {
            val current = counter.get()
            if (current >= config.maxConcurrentAgents) return false
            if (counter.compareAndSet(current, current + 1)) return true
        }
    }

    fun release(projectSlug: String) {
        val counter = activeCounts[projectSlug] ?: return
        counter.updateAndGet { current ->
            if (current > 0) current - 1 else 0
        }
    }

    fun getActiveCount(projectSlug: String): Int {
        return activeCounts[projectSlug]?.get() ?: 0
    }

    fun getRemaining(projectSlug: String, config: QuotaConfig): Int {
        if (config.isUnlimited()) return Int.MAX_VALUE
        return (config.maxConcurrentAgents - getActiveCount(projectSlug)).coerceAtLeast(0)
    }
}
