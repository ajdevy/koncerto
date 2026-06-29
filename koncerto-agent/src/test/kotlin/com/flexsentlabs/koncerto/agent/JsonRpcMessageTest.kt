package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class JsonRpcMessageTest {

    @Test
    fun `JsonRpcRequest defaults to jsonrpc 2_0`() {
        val req = JsonRpcRequest(id = "1", method = "initialize")
        assertThat(req.jsonrpc).isEqualTo("2.0")
        assertThat(req.id).isEqualTo("1")
        assertThat(req.method).isEqualTo("initialize")
        assertThat(req.params).isNull()
    }

    @Test
    fun `JsonRpcRequest with params`() {
        val params = buildJsonObject { put("key", JsonPrimitive("value")) }
        val req = JsonRpcRequest(id = "42", method = "turn/start", params = params)
        assertThat(req.params).isEqualTo(params)
    }

    @Test
    fun `JsonRpcResponse defaults to jsonrpc 2_0`() {
        val resp = JsonRpcResponse(id = "1", result = null)
        assertThat(resp.jsonrpc).isEqualTo("2.0")
        assertThat(resp.id).isEqualTo("1")
        assertThat(resp.result).isNull()
        assertThat(resp.error).isNull()
    }

    @Test
    fun `JsonRpcResponse with error`() {
        val error = JsonRpcError(code = -32600, message = "Invalid Request")
        val resp = JsonRpcResponse(id = "1", error = error)
        assertThat(resp.error).isEqualTo(error)
        assertThat(resp.error!!.code).isEqualTo(-32600)
        assertThat(resp.error!!.message).isEqualTo("Invalid Request")
    }

    @Test
    fun `JsonRpcError with data`() {
        val data = buildJsonObject { put("detail", JsonPrimitive("extra")) }
        val error = JsonRpcError(code = -32000, message = "server error", data = data)
        assertThat(error.data).isEqualTo(data)
    }

    @Test
    fun `JsonRpcError defaults data to null`() {
        val error = JsonRpcError(code = -1, message = "oops")
        assertThat(error.data).isNull()
    }

    @Test
    fun `JsonRpcNotification defaults to jsonrpc 2_0`() {
        val note = JsonRpcNotification(method = "session/started")
        assertThat(note.jsonrpc).isEqualTo("2.0")
        assertThat(note.method).isEqualTo("session/started")
        assertThat(note.params).isNull()
    }

    @Test
    fun `JsonRpcNotification with params`() {
        val params = buildJsonObject { put("thread_id", JsonPrimitive("t1")) }
        val note = JsonRpcNotification(method = "turn/completed", params = params)
        assertThat(note.params).isEqualTo(params)
    }

    @Test
    fun `JsonRpcResponseMsg wraps response`() {
        val resp = JsonRpcResponse(id = "5", result = buildJsonObject { put("ok", JsonPrimitive(true)) })
        val msg = JsonRpcResponseMsg(resp)
        assertThat(msg.response.id).isEqualTo("5")
    }

    @Test
    fun `JsonRpcNotificationMsg wraps notification`() {
        val note = JsonRpcNotification(method = "ping")
        val msg = JsonRpcNotificationMsg(note)
        assertThat(msg.notification.method).isEqualTo("ping")
    }

    @Test
    fun `sealed class hierarchy - both subtypes are JsonRpcMessage`() {
        val respMsg: JsonRpcMessage = JsonRpcResponseMsg(JsonRpcResponse(id = "1"))
        val noteMsg: JsonRpcMessage = JsonRpcNotificationMsg(JsonRpcNotification(method = "m"))
        assertThat(respMsg is JsonRpcMessage).isEqualTo(true)
        assertThat(noteMsg is JsonRpcMessage).isEqualTo(true)
    }
}
