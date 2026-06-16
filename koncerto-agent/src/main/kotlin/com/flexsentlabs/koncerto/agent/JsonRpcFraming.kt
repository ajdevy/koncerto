package com.flexsentlabs.koncerto.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object JsonRpcFraming {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encodeRequest(req: JsonRpcRequest): String = json.encodeToString(JsonRpcRequest.serializer(), req) + "\n"

    fun decodeAll(text: String): List<JsonRpcMessage> {
        val out = mutableListOf<JsonRpcMessage>()
        text.split("\n").forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val obj = json.parseToJsonElement(trimmed).jsonObject
            if ("id" in obj && ("result" in obj || "error" in obj)) {
                val resp = json.decodeFromJsonElement(JsonRpcResponse.serializer(), obj)
                out += JsonRpcResponseMsg(resp)
            } else if ("method" in obj) {
                val note = json.decodeFromJsonElement(JsonRpcNotification.serializer(), obj)
                out += JsonRpcNotificationMsg(note)
            }
        }
        return out
    }
}