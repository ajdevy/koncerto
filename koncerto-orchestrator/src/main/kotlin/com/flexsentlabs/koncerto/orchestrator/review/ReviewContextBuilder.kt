package com.flexsentlabs.koncerto.orchestrator.review

import com.flexsentlabs.koncerto.core.review.ReviewPolicy
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bounded context pack for the reviewer (Epic 20). Assembles issue intent, PR body, the
 * project's review-invariants doc, tests, and neighboring source — filled in priority order
 * until [ReviewPolicy.contextBudgetChars] is exhausted. Composition is reported so it can be
 * logged as a telemetry variable (context is an experiment input, so it must be observable).
 */
class ReviewContextBuilder {

    data class ContextPack(val text: String, val composition: Map<String, Int>)

    fun build(
        workspacePath: Path,
        issueTitle: String,
        issueDescription: String?,
        acceptanceCriteria: String?,
        prBody: String?,
        changedFiles: List<String>,
        policy: ReviewPolicy = ReviewPolicy.DEFAULT
    ): ContextPack {
        val budget = policy.contextBudgetChars
        val sections = LinkedHashMap<String, String>()

        addSection(sections, "intent", buildString {
            appendLine("Issue: $issueTitle")
            if (!issueDescription.isNullOrBlank()) {
                appendLine()
                appendLine(issueDescription.trim())
            }
        }.trim())

        if (!acceptanceCriteria.isNullOrBlank()) {
            addSection(sections, "acceptance_criteria", acceptanceCriteria.trim())
        }
        if (!prBody.isNullOrBlank()) {
            addSection(sections, "pr_body", prBody.trim())
        }

        readInvariants(workspacePath, policy.reviewInvariantsPath)?.let {
            addSection(sections, "invariants", it)
        }

        readNeighbors(workspacePath, changedFiles, remaining(sections, budget))?.let {
            addSection(sections, "neighbors", it)
        }

        // Apply the char budget, dropping/truncating lowest-priority sections last-in-first-out.
        val composition = LinkedHashMap<String, Int>()
        val sb = StringBuilder()
        var used = 0
        for ((name, body) in sections) {
            if (used >= budget) break
            val header = "\n## ${name.replace('_', ' ').replaceFirstChar { it.uppercase() }}\n\n"
            val avail = budget - used - header.length
            if (avail <= 0) break
            val chunk = if (body.length > avail) body.take(avail) + "\n…[truncated]" else body
            sb.append(header).append(chunk).append('\n')
            val added = header.length + chunk.length + 1
            used += added
            composition[name] = chunk.length
        }
        return ContextPack(sb.toString().trim(), composition)
    }

    private fun addSection(map: MutableMap<String, String>, name: String, body: String) {
        if (body.isNotBlank()) map[name] = body
    }

    private fun remaining(sections: Map<String, String>, budget: Int): Int =
        (budget - sections.values.sumOf { it.length }).coerceAtLeast(0)

    private fun readInvariants(workspacePath: Path, relPath: String): String? {
        val p = workspacePath.resolve(relPath)
        return runCatching {
            if (Files.exists(p) && Files.isRegularFile(p)) Files.readString(p).trim() else null
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Test files matching changed sources, then same-directory neighbors (smallest first),
     * capped by [charBudget]. Best-effort; never throws.
     */
    private fun readNeighbors(workspacePath: Path, changedFiles: List<String>, charBudget: Int): String? {
        if (charBudget <= 0 || changedFiles.isEmpty()) return null
        val changedSet = changedFiles.map { it.trimStart('/') }.toHashSet()
        val dirs = changedFiles.mapNotNull { it.substringBeforeLast('/', "").ifBlank { null } }.toSet()
        val candidates = LinkedHashSet<Path>()

        runCatching {
            for (dir in dirs) {
                val dirPath = workspacePath.resolve(dir)
                if (!Files.isDirectory(dirPath)) continue
                Files.list(dirPath).use { stream ->
                    stream.filter { Files.isRegularFile(it) }
                        .filter { p -> workspacePath.relativize(p).toString().replace('\\', '/') !in changedSet }
                        .filter { isSource(it.fileName.toString()) }
                        .sorted(compareBy { runCatching { Files.size(it) }.getOrDefault(Long.MAX_VALUE) })
                        .limit(4)
                        .forEach { candidates.add(it) }
                }
            }
        }

        if (candidates.isEmpty()) return null
        val sb = StringBuilder()
        var used = 0
        for (p in candidates) {
            if (used >= charBudget) break
            val rel = workspacePath.relativize(p).toString().replace('\\', '/')
            val body = runCatching { Files.readString(p) }.getOrNull() ?: continue
            val avail = charBudget - used
            val chunk = if (body.length > avail) body.take(avail) else body
            sb.append("// ---- $rel ----\n").append(chunk).append("\n\n")
            used += chunk.length + rel.length + 12
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun isSource(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf("kt", "kts", "java", "py", "ts", "tsx", "js", "go", "rs", "rb")
    }

    companion object {
        /**
         * Defangs Liquid delimiters in context text.
         *
         * The assembled prompt is Liquid-rendered downstream (AgentRunner renders `{{ issue.* }}`),
         * and the context pack quotes files from the repository under review. Without this, a
         * target repo containing `{{ ... }}` or `{% ... %}` would have that syntax evaluated by
         * our renderer — mangling the context at best, and at worst letting repo content reach
         * the template engine. Context is data; it must never be executable.
         */
        fun neutralizeTemplating(text: String): String = text
            .replace("{{", "{ {")
            .replace("}}", "} }")
            .replace("{%", "{ %")
            .replace("%}", "% }")
    }
}
