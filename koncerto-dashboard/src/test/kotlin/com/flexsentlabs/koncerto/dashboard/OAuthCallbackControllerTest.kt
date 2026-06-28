package com.flexsentlabs.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.agent.AgentAuthChecker
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class OAuthCallbackControllerTest {

    private val controller = OAuthCallbackController()

    private fun setCallbackPort(port: Int?) {
        val field = ApiV1Controller::class.java.getDeclaredField("claudeCallbackPort")
        field.isAccessible = true
        field.set(null, port)
    }

    @BeforeEach
    fun setUp() {
        AgentAuthChecker.reset()
        setCallbackPort(null)
    }

    @AfterEach
    fun tearDown() {
        setCallbackPort(null)
        AgentAuthChecker.reset()
    }

    @Test
    fun `returns 503 when no active claude login session`() {
        val response = controller.handleCallback(mapOf("code" to "abc", "state" to "xyz")).block()!!
        assertThat(response.statusCodeValue).isEqualTo(503)
        assertThat(response.body ?: "").contains("No active Claude login session")
    }

    @Test
    fun `proxies callback to claude process port`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val capturedQuery = StringBuilder()
        server.createContext("/callback") { exchange ->
            capturedQuery.append(exchange.requestURI.rawQuery ?: "")
            val response = "ok".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        val port = server.address.port

        try {
            setCallbackPort(port)
            val response = controller.handleCallback(mapOf("code" to "mycode", "state" to "mystate")).block()!!
            assertThat(response.statusCodeValue).isEqualTo(200)
            assertThat(capturedQuery.toString()).contains("code=mycode")
            assertThat(capturedQuery.toString()).contains("state=mystate")
        } finally {
            server.stop(0)
            setCallbackPort(null)
        }
    }

    @Test
    fun `returns 502 when claude callback server unreachable`() {
        setCallbackPort(19999)
        val response = controller.handleCallback(mapOf("code" to "abc", "state" to "xyz")).block()!!
        assertThat(response.statusCodeValue).isEqualTo(502)
    }
}
