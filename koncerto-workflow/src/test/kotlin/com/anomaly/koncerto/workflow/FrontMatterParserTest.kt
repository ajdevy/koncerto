package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.contains
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions
import org.yaml.snakeyaml.Yaml

class FrontMatterParserTest {

    @Test
    fun `parses YAML front matter and body`() {
        val content = """
            ---
            tracker:
              kind: linear
              project_slug: myproj
            agent:
              max_concurrent_agents: 5
            ---

            # Workflow Prompt

            Hello world
        """.trimIndent()
        val def = FrontMatterParser.parse(content)
        assertThat(def.promptTemplate).isEqualTo("# Workflow Prompt\n\nHello world")
        val cfg = def.config
        @Suppress("UNCHECKED_CAST")
        val tracker = cfg["tracker"] as Map<String, Any?>
        assertThat(tracker["kind"]).isEqualTo("linear")
        assertThat(tracker["project_slug"]).isEqualTo("myproj")
    }

    @Test
    fun `no front matter means empty config and full body as template`() {
        val content = "Just a prompt body"
        val def = FrontMatterParser.parse(content)
        assertThat(def.promptTemplate).isEqualTo("Just a prompt body")
        assertThat(def.config).isEqualTo(emptyMap())
    }

    @Test
    fun `non-map YAML front matter throws`() {
        val content = "---\n- one\n- two\n---\nbody"
        val thrown = Assertions.assertThrows(IllegalStateException::class.java) { FrontMatterParser.parse(content) }
        val msg = thrown.message ?: ""
        assertThat(msg).contains("workflow_front_matter_not_a_map")
    }
}