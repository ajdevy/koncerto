package com.anomaly.koncerto.linear

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration

open class LinearGraphQLClient(
    private val endpoint: String,
    private val apiKey: String?,
    private val timeoutMs: Long = 30_000
) {
    private val client: WebClient = WebClient.builder()
        .baseUrl(endpoint)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    open suspend fun execute(query: String, variables: JsonObject): JsonObject {
        if (apiKey.isNullOrBlank()) throw LinearError.MissingApiKey()
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        return withContext(Dispatchers.IO) {
            try {
                val response = client.post()
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono<JsonObject>()
                    .block(Duration.ofMillis(timeoutMs))
                if (response == null) {
                    throw LinearError.Request("null response")
                }
                if (response["errors"] != null) {
                    throw LinearError.GraphQlErrors(response["errors"].toString())
                }
                response
            } catch (e: LinearError) {
                throw e
            } catch (e: Exception) {
                throw LinearError.Request(e.message ?: "transport failure", e)
            }
        }
    }
}