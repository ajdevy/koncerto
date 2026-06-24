package com.flexsentlabs.koncerto.workflow

import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

class WorkflowCache {
    private val ref = AtomicReference<WorkflowDefinition?>(null)
    @Volatile
    private var _workflowDir: Path? = null

    val workflowDir: Path? get() = _workflowDir

    fun set(def: WorkflowDefinition) { ref.set(def) }
    fun current(): WorkflowDefinition = ref.get() ?: error("workflow not loaded")

    fun setWorkflowDir(dir: Path) { _workflowDir = dir }

    fun resolvePrompt(promptValue: String): String {
        val dir = _workflowDir ?: return promptValue
        val candidate = dir.resolve(promptValue)
        if (Files.exists(candidate)) {
            return Files.readString(candidate)
        }
        return promptValue
    }
}