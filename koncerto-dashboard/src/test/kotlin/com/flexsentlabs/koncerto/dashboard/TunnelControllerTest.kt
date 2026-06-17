package com.flexsentlabs.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
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

    @Test
    fun `tunnel returns active url when ngrok responds with matching tunnel`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/tunnels") { exchange ->
            val json = """{"tunnels":[{"public_url":"https://abc.ngrok.io","config":{"addr":"localhost:17348"}}]}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.executor = null
        server.start()
        try {
            val port = server.address.port
            val controller = TunnelController(ngrokApiUrl = "http://localhost:$port")
            val response = controller.getTunnel().block()
            assertThat(response).isNotNull()
            assertThat(response!!.url).isEqualTo("https://abc.ngrok.io")
            assertThat(response.status).isEqualTo("active")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `tunnel returns inactive when no tunnel matches port`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/tunnels") { exchange ->
            val json = """{"tunnels":[{"public_url":"https://other.ngrok.io","config":{"addr":"localhost:9999"}}]}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.executor = null
        server.start()
        try {
            val port = server.address.port
            val controller = TunnelController(ngrokApiUrl = "http://localhost:$port")
            val response = controller.getTunnel().block()
            assertThat(response).isNotNull()
            assertThat(response!!.status).isEqualTo("inactive")
            assertThat(response.url).isNull()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `tunnel returns inactive when tunnels list is empty`() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/api/tunnels") { exchange ->
            val json = """{"tunnels":[]}"""
            val bytes = json.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.write(bytes)
            exchange.responseBody.close()
        }
        server.executor = null
        server.start()
        try {
            val port = server.address.port
            val controller = TunnelController(ngrokApiUrl = "http://localhost:$port")
            val response = controller.getTunnel().block()
            assertThat(response).isNotNull()
            assertThat(response!!.status).isEqualTo("inactive")
            assertThat(response.url).isNull()
        } finally {
            server.stop(0)
        }
    }
}
