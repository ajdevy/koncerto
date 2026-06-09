package com.anomaly.koncerto.core.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WorkflowDefinitionTest {

    @Test
    fun `stores config and promptTemplate`() {
        val config = mapOf("tracker" to mapOf("kind" to "linear"))
        val def = WorkflowDefinition(config, "Hello {{ issue.identifier }}")
        assertThat(def.config).isEqualTo(config)
        assertThat(def.promptTemplate).isEqualTo("Hello {{ issue.identifier }}")
    }

    @Test
    fun `data class structural equality`() {
        val a = WorkflowDefinition(mapOf("key" to "value"), "template")
        val b = WorkflowDefinition(mapOf("key" to "value"), "template")
        assertThat(a).isEqualTo(b)
    }
}
