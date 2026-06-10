package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.config.SubtaskManifest
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.logging.StructuredLogger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class WorkplanParser(
    private val logger: StructuredLogger? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(workspacePath: Path): Result<SubtaskManifest, ParseError> {
        val file = workspacePath.resolve("_koncerto").resolve("workplan.json")
        if (!Files.exists(file)) {
            return Result.Failure(ParseError.NOT_FOUND)
        }
        return try {
            val content = Files.readString(file)
            val manifest = json.decodeFromString<SubtaskManifest>(content)
            validate(manifest)
            Result.Success(manifest)
        } catch (e: Exception) {
            when (e) {
                is ParseError -> Result.Failure(e)
                else -> Result.Failure(ParseError.INVALID(e.message ?: "unknown"))
            }
        }
    }

    private fun validate(manifest: SubtaskManifest) {
        if (manifest.subtasks.isEmpty()) {
            throw ParseError.INVALID("subtasks list is empty")
        }

        val ids = manifest.subtasks.map { it.id }
        if (ids.size != ids.distinct().size) {
            throw ParseError.INVALID("duplicate subtask IDs found")
        }

        val idSet = ids.toSet()
        for (subtask in manifest.subtasks) {
            for (dep in subtask.dependsOn) {
                if (dep !in idSet) {
                    throw ParseError.INVALID(
                        "subtask '${subtask.id}' depends on nonexistent subtask '$dep'"
                    )
                }
            }
        }

        detectCycle(manifest)
    }

    private fun detectCycle(manifest: SubtaskManifest) {
        val adjacency = manifest.subtasks.associate { it.id to it.dependsOn.toSet() }
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(id: String) {
            if (id in inStack) throw ParseError.INVALID("circular dependency detected")
            if (id in visited) return
            visited.add(id)
            inStack.add(id)
            for (dep in adjacency[id].orEmpty()) {
                dfs(dep)
            }
            inStack.remove(id)
        }

        for (id in adjacency.keys) {
            if (id !in visited) dfs(id)
        }
    }
}

sealed class ParseError : Throwable() {
    data object NOT_FOUND : ParseError()
    data class INVALID(override val message: String) : ParseError()
}