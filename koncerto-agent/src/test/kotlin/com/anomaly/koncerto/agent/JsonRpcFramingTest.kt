package com.anomaly.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import org.junit.jupiter.api.Test

class JsonRpcFramingTest {

    @Test
    fun `encodeRequest appends newline`() {
        val req = JsonRpcRequest(id = "1", method = "initialize")
        val encoded = JsonRpcFraming.encodeRequest(req)
        assertThat(encoded.endsWith("\n")).isEqualTo(true)
        assertThat(encoded.contains("\"method\":\"initialize\"")).isEqualTo(true)
    }

    @Test
    fun `decodeAll parses notification`() {
        val line = """{"jsonrpc":"2.0","method":"session/started","params":{"thread_id":"t1"}}"""
        val msgs = JsonRpcFraming.decodeAll(line)
        assertThat(msgs.size).isEqualTo(1)
        val notif = msgs[0] as JsonRpcNotificationMsg
        assertThat(notif.notification.method).isEqualTo("session/started")
    }

    @Test
    fun `decodeAll parses response`() {
        val line = """{"jsonrpc":"2.0","id":"1","result":{"status":"ok"}}"""
        val msgs = JsonRpcFraming.decodeAll(line)
        assertThat(msgs.size).isEqualTo(1)
        val resp = msgs[0] as JsonRpcResponseMsg
        assertThat(resp.response.id).isEqualTo("1")
    }

    @Test
    fun `decodeAll skips blank lines`() {
        val text = "\n\n{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n\n"
        val msgs = JsonRpcFraming.decodeAll(text)
        assertThat(msgs.size).isEqualTo(1)
    }
}
