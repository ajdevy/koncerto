package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.core.model.TokenUsage
import com.flexsentlabs.koncerto.notifications.channel.WebhookNotifier
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import assertk.assertThat
import assertk.assertions.*

class WebhookNotifierTest {

    @Test
    fun `sends post with correct json body for completed event`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestBody = ""
        var requestMethod = ""
        var contentType = ""
        server.createContext("/hook") { exchange ->
            requestMethod = exchange.requestMethod
            contentType = exchange.requestHeaders.getFirst("Content-Type") ?: ""
            requestBody = exchange.requestBody.reader().readText()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val port = server.address.port
            val notifier = WebhookNotifier("http://localhost:$port/hook")
            val event = NotificationEvent.AgentCompleted(
                projectSlug = "test-proj",
                issueId = "42",
                issueIdentifier = "TEST-42",
                title = "Completed task",
                tokenUsage = TokenUsage(100, 50, 150)
            )
            notifier.send(event)

            assertThat(requestMethod).isEqualTo("POST")
            assertThat(contentType).isEqualTo("application/json")

            val json = Json.parseToJsonElement(requestBody).jsonObject
            assertThat(json["event"]!!.jsonPrimitive.content).isEqualTo("AgentCompleted")
            assertThat(json["projectSlug"]!!.jsonPrimitive.content).isEqualTo("test-proj")
            assertThat(json["issueId"]!!.jsonPrimitive.content).isEqualTo("42")
            assertThat(json["issueIdentifier"]!!.jsonPrimitive.content).isEqualTo("TEST-42")
            assertThat(json["title"]!!.jsonPrimitive.content).isEqualTo("Completed task")
            assertThat(json["error"]).isNull()
            assertThat(json["stallDurationMs"]).isNull()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sends error field for agent failed event`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestBody = ""
        server.createContext("/hook") { exchange ->
            requestBody = exchange.requestBody.reader().readText()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val port = server.address.port
            val notifier = WebhookNotifier("http://localhost:$port/hook")
            val event = NotificationEvent.AgentFailed("p", "1", "P-1", "Failed", "Something went wrong")
            notifier.send(event)

            val json = Json.parseToJsonElement(requestBody).jsonObject
            assertThat(json["event"]!!.jsonPrimitive.content).isEqualTo("AgentFailed")
            assertThat(json["error"]!!.jsonPrimitive.content).isEqualTo("Something went wrong")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sends stall duration for agent stalled event`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var requestBody = ""
        server.createContext("/hook") { exchange ->
            requestBody = exchange.requestBody.reader().readText()
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val port = server.address.port
            val notifier = WebhookNotifier("http://localhost:$port/hook")
            val event = NotificationEvent.AgentStalled("p", "1", "P-1", "Stalled", 60_000L)
            notifier.send(event)

            val json = Json.parseToJsonElement(requestBody).jsonObject
            assertThat(json["event"]!!.jsonPrimitive.content).isEqualTo("AgentStalled")
            assertThat(json["stallDurationMs"]!!.jsonPrimitive.content).isEqualTo("60000")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `sends custom headers`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        var authHeader = ""
        server.createContext("/hook") { exchange ->
            authHeader = exchange.requestHeaders.getFirst("Authorization") ?: ""
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        server.executor = Executors.newSingleThreadExecutor()
        server.start()
        try {
            val port = server.address.port
            val notifier = WebhookNotifier(
                url = "http://localhost:$port/hook",
                headers = mapOf("Authorization" to "Bearer secret-123")
            )
            val event = NotificationEvent.ClarificationRequested("p", "1", "P-1", "Clarify")
            notifier.send(event)

            assertThat(authHeader).isEqualTo("Bearer secret-123")
        } finally {
            server.stop(0)
        }
    }
}
