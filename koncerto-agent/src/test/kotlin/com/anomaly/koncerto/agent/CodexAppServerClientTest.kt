package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import com.anomaly.koncerto.logging.LogSink
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test

class CodexAppServerClientTest {

    @Test
    fun `client spawns and receives stdout as events`() {
        val ws = Files.createTempDirectory("agent-test-")
        val script = """
            printf '%s\n' '{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1","turn_id":"u1"}}'
            sleep 0.2
        """.trimIndent()
        val logger = StructuredLogger(listOf(object : LogSink {
            override fun write(line: String) {}
        }))
        val client = CodexAppServerClient(script, ws, logger)
        runBlocking {
            assertThat(client.start()).isEqualTo(true)
        }

        // Wait for the process and reader to finish, then drain events
        val collected = mutableListOf<AgentEvent>()
        runBlocking {
            withTimeoutOrNull(5_000) {
                client.events().collect { ev ->
                    collected += ev
                }
            }
        }
        client.stop()
        assertThat(collected.filterIsInstance<AgentEvent.SessionStarted>().firstOrNull()).isNotNull()
    }
}
