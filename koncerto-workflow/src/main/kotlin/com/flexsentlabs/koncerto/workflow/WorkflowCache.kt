package com.flexsentlabs.koncerto.workflow

import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import java.util.concurrent.atomic.AtomicReference

class WorkflowCache {
    private val ref = AtomicReference<WorkflowDefinition?>(null)

    fun set(def: WorkflowDefinition) { ref.set(def) }
    fun current(): WorkflowDefinition = ref.get() ?: error("workflow not loaded")
}