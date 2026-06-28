package com.flexsentlabs.koncerto.workflow

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.io.path.createTempDirectory

class WorkflowCacheTest {

    @Test
    fun `set stores definition`() {
        val cache = WorkflowCache()
        val def = WorkflowDefinition(emptyMap(), "template")
        cache.set(def)
        assertThat(cache.current()).isEqualTo(def)
    }

    @Test
    fun `current returns stored definition`() {
        val cache = WorkflowCache()
        val def = WorkflowDefinition(mapOf("key" to "value"), "hello")
        cache.set(def)
        assertThat(cache.current().promptTemplate).isEqualTo("hello")
        assertThat(cache.current().config["key"]).isEqualTo("value")
    }

    @Test
    fun `current throws when not set`() {
        val cache = WorkflowCache()
        assertThrows<IllegalStateException> {
            cache.current()
        }
    }

    @Test
    fun `workflowDir defaults to null`() {
        val cache = WorkflowCache()
        assertThat(cache.workflowDir).isNull()
    }

    @Test
    fun `setWorkflowDir stores directory`() {
        val cache = WorkflowCache()
        val dir = createTempDirectory("workflow-cache-test")
        cache.setWorkflowDir(dir)
        assertThat(cache.workflowDir).isEqualTo(dir)
    }

    @Test
    fun `resolvePrompt returns value when workflowDir not set`() {
        val cache = WorkflowCache()
        assertThat(cache.resolvePrompt("prompts/implement.md")).isEqualTo("prompts/implement.md")
    }

    @Test
    fun `resolvePrompt reads file when it exists under workflowDir`() {
        val cache = WorkflowCache()
        val dir = createTempDirectory("workflow-cache-test")
        val promptFile = dir.resolve("prompts/implement.md")
        Files.createDirectories(promptFile.parent)
        Files.writeString(promptFile, "Implement this feature")
        cache.setWorkflowDir(dir)
        assertThat(cache.resolvePrompt("prompts/implement.md")).isEqualTo("Implement this feature")
    }

    @Test
    fun `resolvePrompt returns value when file does not exist`() {
        val cache = WorkflowCache()
        val dir = createTempDirectory("workflow-cache-test")
        cache.setWorkflowDir(dir)
        assertThat(cache.resolvePrompt("prompts/missing.md")).isEqualTo("prompts/missing.md")
    }
}
