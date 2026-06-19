package com.flexsentlabs.koncerto.agent

import java.util.concurrent.TimeUnit

object AgentAuthChecker {
    private val cache = mutableMapOf<String, Boolean>()
    private var lastChecked = 0L
    private const val cacheTtlMs = 30_000L

    fun isAuthenticated(agentKind: String): Boolean {
        val key = agentKind.lowercase().trim()
        if (!needsAuth(key)) return true
        refreshCache()
        return cache[key] ?: false
    }

    fun needsAuth(agentKind: String): Boolean = agentKind.lowercase().trim() in AGENTS_NEEDING_AUTH

    private fun refreshCache() {
        val now = System.currentTimeMillis()
        if (now - lastChecked < cacheTtlMs && cache.isNotEmpty()) return
        lastChecked = now
        cache.clear()
        cache["codex"] = checkCodex()
    }

    private fun checkCodex(): Boolean {
        try {
            val pb = ProcessBuilder("bash", "-lc", "codex login status")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val exited = p.waitFor(5, TimeUnit.SECONDS)
            return exited && p.exitValue() == 0
        } catch (_: Exception) {
            return false
        }
    }

    private val AGENTS_NEEDING_AUTH = setOf("codex")
}
