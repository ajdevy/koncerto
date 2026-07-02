package com.flexsentlabs.koncerto.workspace

import java.nio.file.Files
import java.nio.file.Path

object GitRemoteResolver {
    private val GITHUB_REPO_FROM_URL = Regex("""github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?$""")
    private val GITHUB_REPO_IN_CONFIG = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?\s*$""")

    fun repoFullNameFromRemoteUrl(remoteUrl: String): String? {
        if (remoteUrl.isBlank()) return null
        return GITHUB_REPO_FROM_URL.find(remoteUrl)?.groupValues?.get(1)
    }

    fun repoFullName(workspacePath: Path): String? {
        val configPath = resolveGitConfigPath(workspacePath) ?: return null
        return try {
            repoFullNameFromGitConfig(Files.readString(configPath))
        } catch (_: Exception) {
            null
        }
    }

    fun repoFullNameFromGitConfig(content: String): String? {
        val originIdx = content.indexOf("[remote \"origin\"]")
        if (originIdx < 0) return null
        return GITHUB_REPO_IN_CONFIG.find(content, originIdx)?.groupValues?.get(1)
    }

    fun resolveGitConfigPath(workspacePath: Path): Path? {
        val directConfig = workspacePath.resolve(".git/config")
        if (Files.exists(directConfig)) return directConfig
        val gitFile = workspacePath.resolve(".git")
        if (!Files.exists(gitFile) || !Files.isRegularFile(gitFile)) return null
        return try {
            val gitDirLine = Files.readString(gitFile).trim()
            val prefix = "gitdir: "
            if (!gitDirLine.startsWith(prefix)) return null
            Path.of(gitDirLine.removePrefix(prefix).trim()).resolve("config")
        } catch (_: Exception) {
            null
        }
    }

    fun sanitizeRemoteUrl(url: String): String =
        url.replace(Regex("""//[^@]+@"""), "//")
}
