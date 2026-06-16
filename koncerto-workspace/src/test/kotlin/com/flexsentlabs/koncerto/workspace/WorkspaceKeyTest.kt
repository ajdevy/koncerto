package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class WorkspaceKeyTest {

    @Test
    fun `keeps safe characters`() {
        assertThat(WorkspaceKey.sanitize("ABC-123")).isEqualTo("ABC-123")
        assertThat(WorkspaceKey.sanitize("a.b_c-d")).isEqualTo("a.b_c-d")
        assertThat(WorkspaceKey.sanitize("Feature/X")).isEqualTo("Feature_X")
    }

    @Test
    fun `replaces spaces and special chars with underscore`() {
        assertThat(WorkspaceKey.sanitize("My Issue!")).isEqualTo("My_Issue_")
        assertThat(WorkspaceKey.sanitize("foo@bar")).isEqualTo("foo_bar")
    }

    @Test
    fun `empty string returns single underscore`() {
        assertThat(WorkspaceKey.sanitize("")).isEqualTo("_")
    }
}