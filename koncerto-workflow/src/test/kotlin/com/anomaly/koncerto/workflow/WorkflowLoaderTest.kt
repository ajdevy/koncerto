package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import java.nio.file.Files
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkflowLoaderTest {

    @Test
    fun `loads workflow from file path`() {
        val tmp = Files.createTempFile("workflow", ".md")
        Files.writeString(tmp, """
            ---
            tracker:
              kind: linear
              project_slug: p
            ---

            Hello {{ issue.identifier }}
        """.trimIndent())
        val def = WorkflowLoader.loadFromPath(tmp)
        assertThat(def.promptTemplate).contains("Hello {{ issue.identifier }}")
    }

    @Test
    fun `invalid front matter throws workflow_parse_error`() {
        val tmp = Files.createTempFile("workflow-bad", ".md")
        Files.writeString(tmp, "---\ninvalid: [\n---\nbody")
        val ex = assertThrows<IllegalStateException> {
            WorkflowLoader.loadFromPath(tmp)
        }
        assertThat(ex.message ?: "").contains("workflow_front_matter_not_a_map")
    }

    @Test
    fun `missing file throws missing_workflow_file error`() {
        val ex = assertThrows<IllegalStateException> {
            WorkflowLoader.loadFromPath(java.nio.file.Paths.get("/nonexistent/WORKFLOW.md"))
        }
        assertThat(ex.message ?: "").contains("missing_workflow_file")
    }

    @Test
    fun `cache stores latest loaded workflow`() {
        val tmp = Files.createTempFile("workflow-cache", ".md")
        Files.writeString(tmp, "---\ntracker:\n  kind: linear\n---\nbody v1")
        val cache = WorkflowCache()
        val def = WorkflowLoader.loadInto(tmp, cache)
        assertThat(def.promptTemplate).isEqualTo("body v1")
        assertThat(cache.current().promptTemplate).isEqualTo("body v1")
    }
}