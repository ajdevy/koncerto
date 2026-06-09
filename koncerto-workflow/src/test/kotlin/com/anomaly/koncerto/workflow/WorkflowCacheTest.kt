package com.anomaly.koncerto.workflow

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.core.config.WorkflowDefinition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
}
