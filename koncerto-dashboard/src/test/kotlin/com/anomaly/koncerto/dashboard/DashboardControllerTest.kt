package com.anomaly.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class DashboardControllerTest {

    @Test
    fun `dashboard returns HTML content`() {
        val controller = DashboardController()
        val html = controller.dashboard().block()

        assertThat(html!!.contains("Koncerto Dashboard")).isTrue()
        assertThat(html.contains("/api/v1/state")).isTrue()
        assertThat(html.contains("<table")).isTrue()
    }
}
