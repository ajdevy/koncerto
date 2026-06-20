package com.flexsentlabs.koncerto.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AgentAuthChecker {
    private val cache = ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastChecked = 0L
    private const val cacheTtlMs = 30_000L
    private val overrideAuth = ConcurrentHashMap<String, Boolean>()

    private val claudeApiKeyPath: Path = Path.of(System.getProperty("user.home", "/root"), ".claude", "api_key")

    private val claudeHasApiKey: Boolean
        get() = System.getenv("ANTHROPIC_API_KEY")?.let { it.isNotBlank() } == true ||
            (Files.exists(claudeApiKeyPath) && Files.readString(claudeApiKeyPath).isNotBlank())

    fun isAuthenticated(agentKind: String): Boolean {
        val key = agentKind.lowercase().trim()
        if (!needsAuth(key)) return true
        overrideAuth[key]?.let { return it }
        refreshCache()
        return cache[key] ?: false
    }

    fun needsAuth(agentKind: String): Boolean = agentKind.lowercase().trim() in AGENTS_NEEDING_AUTH

    fun markAuthenticated(agentKind: String) {
        overrideAuth[agentKind.lowercase().trim()] = true
    }

    fun reset() {
        overrideAuth.clear()
        cache.clear()
        lastChecked = 0L
    }

    /** Get the stored Claude API key, or null if not configured */
    fun getClaudeApiKey(): String? {
        System.getenv("ANTHROPIC_API_KEY")?.let { if (it.isNotBlank()) return it }
        return try {
            if (Files.exists(claudeApiKeyPath)) {
                Files.readString(claudeApiKeyPath).trim().ifBlank { null }
            } else null
        } catch (_: Exception) { null }
    }

    /** Store a Claude API key to a file on the volume */
    fun setClaudeApiKey(key: String) {
        try {
            Files.createDirectories(claudeApiKeyPath.parent)
            Files.writeString(claudeApiKeyPath, key.trim())
            cache["claude"] = true
        } catch (_: Exception) {
        }
    }

    private fun refreshCache() {
        val now = System.currentTimeMillis()
        if (now - lastChecked < cacheTtlMs && cache.isNotEmpty()) return
        lastChecked = now
        cache.clear()
        cache["codex"] = checkCodex()
        cache["claude"] = claudeHasApiKey
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

    private val AGENTS_NEEDING_AUTH = setOf("codex", "claude")
}
