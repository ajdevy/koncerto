package com.flexsentlabs.koncerto.notifications

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.notifications.channel.TelegramNotifier
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * These tests are named "send formats ..." so they assert the formatting — they previously
 * called send() against the real api.telegram.org and asserted nothing, which made them both
 * network-dependent and incapable of catching a formatting regression.
 *
 * A loopback HttpServer stands in for the Telegram API, so the tests are hermetic and can
 * inspect the exact payload that would have been sent.
 */
class TelegramNotifierTest {

    private lateinit var server: HttpServer
    private val lastBody = AtomicReference<String?>(null)
    private val lastPath = AtomicReference<String?>(null)

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastPath.set(exchange.requestURI.path)
            lastBody.set(exchange.requestBody.readBytes().decodeToString())
            val response = """{"ok":true}"""
            exchange.sendResponseHeaders(200, response.length.toLong())
            exchange.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun notifier() = TelegramNotifier(
        botToken = "12345:ABC-DEF",
        chatId = "-1001234567890",
        baseUrl = "http://127.0.0.1:${server.address.port}"
    )

    @Test
    fun `constructs with valid bot token and chat id`() {
        TelegramNotifier(botToken = "12345:ABC-DEF", chatId = "-1001234567890")
    }

    @Test
    fun `send posts to the bot sendMessage endpoint with the chat id`() = runTest {
        notifier().send(NotificationEvent.AgentCompleted("p", "1", "P-1", "Completed task", null))

        assertThat(lastPath.get()).isEqualTo("/bot12345:ABC-DEF/sendMessage")
        assertThat(lastBody.get()!!).contains("\"chat_id\":\"-1001234567890\"")
        assertThat(lastBody.get()!!).contains("\"parse_mode\":\"Markdown\"")
    }

    @Test
    fun `send formats agent completed event`() = runTest {
        notifier().send(NotificationEvent.AgentCompleted("p", "1", "P-1", "Completed task", null))
        assertThat(lastBody.get()!!).contains("✅ *P-1*: Completed task")
    }

    @Test
    fun `send formats agent failed event`() = runTest {
        notifier().send(NotificationEvent.AgentFailed("p", "2", "P-2", "Failed task", "Error details"))
        assertThat(lastBody.get()!!).contains("❌ *P-2*: Failed task")
    }

    @Test
    fun `send formats agent stalled event`() = runTest {
        notifier().send(NotificationEvent.AgentStalled("p", "3", "P-3", "Stalled task", 60_000L))
        assertThat(lastBody.get()!!).contains("⚠️ *P-3*: Stalled task")
    }

    @Test
    fun `send formats clarification requested event`() = runTest {
        notifier().send(NotificationEvent.ClarificationRequested("p", "4", "P-4", "Clarification needed"))
        assertThat(lastBody.get()!!).contains("❓ *P-4*: Clarification needed")
    }

    @Test
    fun `send formats limit detected event`() = runTest {
        notifier().send(
            NotificationEvent.LimitDetected("p", "5", "P-5", "Rate limit", "rate_limit", "Too many requests", 30_000L)
        )
        assertThat(lastBody.get()!!).contains("⛔ *P-5*: Rate limit")
    }
}
