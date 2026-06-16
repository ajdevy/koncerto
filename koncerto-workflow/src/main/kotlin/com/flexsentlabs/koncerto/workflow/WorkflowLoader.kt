package com.flexsentlabs.koncerto.workflow

import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import java.nio.file.Path

object WorkflowLoader {

    fun loadFromPath(path: Path): WorkflowDefinition {
        if (!java.nio.file.Files.exists(path)) {
            throw IllegalStateException("missing_workflow_file: $path")
        }
        val content = java.nio.file.Files.readString(path)
        return try {
            FrontMatterParser.parse(content)
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("workflow_parse_error: ${e.message}", e)
        }
    }

    fun loadInto(path: Path, cache: WorkflowCache): WorkflowDefinition {
        val def = loadFromPath(path)
        cache.set(def)
        return def
    }
}