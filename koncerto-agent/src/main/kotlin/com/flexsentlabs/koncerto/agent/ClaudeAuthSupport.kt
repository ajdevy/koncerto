package com.flexsentlabs.koncerto.agent

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object ClaudeAuthSupport {
    private const val tokenEnvVar = "CLAUDE_CODE_OAUTH_TOKEN"
    private const val tokenPathProperty = "koncerto.claude.auth.token.path"
    private val defaultTokenPath: Path = Paths.get("/data/claude-oauth-token")
    private val tokenRegex = Regex("""sk-ant-[A-Za-z0-9_-]+""")

    fun loadToken(): String? = runCatching {
        Files.readString(tokenPath()).trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun saveToken(token: String) {
        val clean = token.trim().takeIf { it.isNotBlank() } ?: return
        val path = tokenPath()
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, clean)
    }

    fun clearToken() {
        runCatching { Files.deleteIfExists(tokenPath()) }
    }

    fun extractToken(output: String): String? {
        val lines = output.lineSequence().toList()
        val markerIndex = lines.indexOfFirst { it.contains("Your OAuth token") }
        if (markerIndex == -1) return null

        val tokenBlock = lines.drop(markerIndex + 1)
            .takeWhile { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() &&
                    !trimmed.startsWith("Store this token") &&
                    !trimmed.startsWith("Use this token") &&
                    !trimmed.startsWith("Paste code here")
            }
            .joinToString(separator = "") { it.trim() }
            .replace(Regex("""\s+"""), "")

        return tokenRegex.find(tokenBlock)?.value
    }

    fun applyToken(processBuilder: ProcessBuilder) {
        loadToken()?.let { token ->
            processBuilder.environment()[tokenEnvVar] = token
        }
    }

    private fun tokenPath(): Path = Paths.get(
        System.getProperty(tokenPathProperty, defaultTokenPath.toString())
    )
}
