package com.flexsentlabs.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class TunnelControllerTest {

    @Test
    fun `tunnel returns inactive when ngrok is not reachable`() {
        val controller = TunnelController(ngrokApiUrl = "http://localhost:1")
        val response = controller.getTunnel().block()

        assertThat(response).isNotNull()
        assertThat(response!!.status).isEqualTo("inactive")
        assertThat(response.url).isNull()
    }

    @Test
    fun `tunnel returns inactive for empty tunnels response`() {
        val controller = TunnelController(ngrokApiUrl = "http://localhost:1")
        val response = controller.getTunnel().block()

        assertThat(response).isNotNull()
        assertThat(response.status).isEqualTo("inactive")
        assertThat(response.url).isNull()
    }

    @Test
    fun `controller constructs with default ngrok URL`() {
        val controller = TunnelController()
        assertThat(controller).isNotNull()
    }
}
