package com.flexsentlabs.koncerto.deploy

import java.nio.file.Files
import java.nio.file.Path

/**
 * Detects the environment variables a target project *declares* it requires, so a missing-secret
 * preflight can block a ticket before any agent runs.
 *
 * Detection is deterministic and declaration-file based (never source scanning). Each supported
 * ecosystem convention is a small parser; results are unioned. The guiding rule is "declared with
 * no usable default → required":
 *  - `.env` templates (`.env.example/.sample/.template/.dist`): an **empty** value means the author
 *    left it for the operator to fill → required. Any non-empty value is a working default.
 *  - Spring `application*.{properties,yml,yaml}`: a `${VAR}` placeholder with no `:default` → required.
 *  - docker-compose `environment:`: an entry with no inline default (bare `- VAR`, `VAR:` empty, or
 *    `VAR: ${VAR}` without `:-default`) → required.
 *
 * When a project declares nothing detectable, the result is empty and no preflight gate applies.
 */
class SecretRequirementDetector {

    fun detect(projectPath: Path): Set<String> {
        val required = linkedSetOf<String>()
        required += fromEnvTemplates(projectPath)
        required += fromSpringConfig(projectPath)
        required += fromCompose(projectPath)
        return required
    }

    private fun fromEnvTemplates(projectPath: Path): Set<String> {
        val names = linkedSetOf<String>()
        val templates = listOf(".env.example", ".env.sample", ".env.template", ".env.dist")
        for (name in templates) {
            val file = projectPath.resolve(name)
            if (!Files.exists(file)) continue
            for (raw in runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())) {
                val line = raw.trim().removePrefix("export ").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                if (key.isEmpty() || !isEnvName(key)) continue
                val value = stripInlineComment(line.substring(eq + 1).trim()).trim('"', '\'').trim()
                if (value.isEmpty()) names += key
            }
        }
        return names
    }

    private fun fromSpringConfig(projectPath: Path): Set<String> {
        val names = linkedSetOf<String>()
        val configs = runCatching {
            Files.walk(projectPath, 6).use { stream ->
                stream.filter { Files.isRegularFile(it) }
                    .filter { p ->
                        val n = p.fileName.toString()
                        (n.startsWith("application") && (n.endsWith(".properties") || n.endsWith(".yml") || n.endsWith(".yaml"))) &&
                            !p.toString().contains("${java.io.File.separator}test${java.io.File.separator}")
                    }
                    .toList()
            }
        }.getOrDefault(emptyList())
        // ${VAR} with no `:default` is required; ${VAR:default} carries its own fallback.
        val placeholder = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)(:[^}]*)?}""")
        for (cfg in configs) {
            val text = runCatching { Files.readString(cfg) }.getOrNull() ?: continue
            for (m in placeholder.findAll(text)) {
                if (m.groupValues[2].isEmpty()) names += m.groupValues[1]
            }
        }
        return names
    }

    private fun fromCompose(projectPath: Path): Set<String> {
        val names = linkedSetOf<String>()
        val composeFiles = listOf(
            "docker-compose.yml", "docker-compose.yaml",
            "docker-compose.demo.yml", "docker-compose.prod.yml", "docker-compose.dev.yml"
        )
        // Match any ${VAR<op>...} interpolation and capture the operator. Only `:-` / `-` supply a
        // usable default when the var is unset → not required. Everything else — plain ${VAR},
        // required markers ${VAR:?err} / ${VAR?err}, and alternate-value ${VAR:+x} / ${VAR+x} (which
        // yield empty, not a default, when unset) → required. The earlier regex only understood
        // `:-`/`-` and silently skipped `:?`/`?` entirely, missing the strongest "required" signal.
        val ref = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)([:+?-][^}]*)?}""")
        for (name in composeFiles) {
            val file = projectPath.resolve(name)
            if (!Files.exists(file)) continue
            val lines = runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
            for (raw in lines) {
                val line = stripInlineComment(raw).trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                for (m in ref.findAll(line)) {
                    val op = m.groupValues[2]
                    val hasDefault = op.startsWith(":-") || op.startsWith("-")
                    if (!hasDefault) names += m.groupValues[1]
                }
                // Bare list entry `- VAR` (no `=`) passes the host env var straight through → required.
                if (line.startsWith("- ")) {
                    val entry = line.removePrefix("- ").trim()
                    if (!entry.contains('=') && !entry.contains(':') && isEnvName(entry)) names += entry
                }
            }
        }
        return names
    }

    private fun stripInlineComment(value: String): String {
        // Drop a trailing ` # comment` but keep `#` that is part of a quoted/among-value token.
        val hash = value.indexOf(" #")
        return if (hash >= 0) value.substring(0, hash) else value
    }

    private fun isEnvName(s: String): Boolean = s.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
}
