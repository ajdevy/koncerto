package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class HooksConfigTest {

    @Test
    fun `stores all fields`() {
        val hooks = HooksConfig(
            afterCreate = "echo created",
            beforeRun = "echo before",
            afterRun = "echo after",
            beforeRemove = "echo remove",
            timeoutMs = 30_000L
        )
        assertThat(hooks.afterCreate).isEqualTo("echo created")
        assertThat(hooks.beforeRun).isEqualTo("echo before")
        assertThat(hooks.afterRun).isEqualTo("echo after")
        assertThat(hooks.beforeRemove).isEqualTo("echo remove")
        assertThat(hooks.timeoutMs).isEqualTo(30_000L)
    }

    @Test
    fun `null fields are null`() {
        val hooks = HooksConfig(null, null, null, null, 60_000L)
        assertThat(hooks.afterCreate).isNull()
        assertThat(hooks.beforeRun).isNull()
        assertThat(hooks.afterRun).isNull()
        assertThat(hooks.beforeRemove).isNull()
    }

    @Test
    fun `copy preserves unchanged fields`() {
        val hooks = HooksConfig("a", "b", "c", "d", 5000L)
        val copied = hooks.copy(timeoutMs = 10_000L)
        assertThat(copied.afterCreate).isEqualTo("a")
        assertThat(copied.timeoutMs).isEqualTo(10_000L)
    }
}
