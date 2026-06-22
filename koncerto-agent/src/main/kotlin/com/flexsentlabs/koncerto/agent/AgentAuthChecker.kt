package com.flexsentlabs.koncerto.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AgentAuthChecker {
    private val cache = ConcurrentHashMap<String, Boolean>()
    @Volatile private var lastChecked = 0L
    private const val cacheTtlMs = 30_000L
    private val overrideAuth = ConcurrentHashMap<String, Boolean>()
    @Volatile private var claudeAuthToken: String? = null

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

    fun setClaudeAuthToken(token: String) {
        claudeAuthToken = token.trim().takeIf { it.isNotBlank() }
        claudeAuthToken?.let {
            ClaudeAuthSupport.saveToken(it)
            markAuthenticated("claude")
        }
    }

    fun getClaudeAuthToken(): String? = claudeAuthToken ?: ClaudeAuthSupport.loadToken()?.also {
        claudeAuthToken = it
    }

    @Deprecated("Use setClaudeAuthToken", ReplaceWith("setClaudeAuthToken(token)"))
    fun setClaudeApiKey(key: String) = setClaudeAuthToken(key)

    @Deprecated("Use getClaudeAuthToken", ReplaceWith("getClaudeAuthToken()"))
    fun getClaudeApiKey(): String? = getClaudeAuthToken()

    fun reset() {
        overrideAuth.clear()
        cache.clear()
        lastChecked = 0L
        claudeAuthToken = null
    }

    private fun refreshCache() {
        val now = System.currentTimeMillis()
        if (now - lastChecked < cacheTtlMs && cache.isNotEmpty()) return
        lastChecked = now
        cache.clear()
        cache["codex"] = checkCodex()
        cache["claude"] = checkClaude()
    }

    private fun checkClaude(): Boolean {
        try {
            val pb = ProcessBuilder("bash", "-lc", "claude auth status --json")
            pb.redirectErrorStream(true)
            ClaudeAuthSupport.applyToken(pb)
            val p = pb.start()
            val exited = p.waitFor(5, TimeUnit.SECONDS)
            if (!exited) {
                p.destroyForcibly()
                return false
            }
            val output = p.inputStream.bufferedReader().readText()
            return p.exitValue() == 0 && output.contains("\"loggedIn\": true")
        } catch (_: Exception) {
            return false
        }
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
