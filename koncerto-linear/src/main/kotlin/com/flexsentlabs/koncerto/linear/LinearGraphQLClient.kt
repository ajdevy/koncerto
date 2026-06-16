package com.flexsentlabs.koncerto.linear

import com.flexsentlabs.koncerto.core.circuitbreaker.ProviderCircuitBreaker
import com.flexsentlabs.koncerto.core.ratelimit.RateLimitProvider
import com.flexsentlabs.koncerto.core.retry.RetryConfig
import com.flexsentlabs.koncerto.core.retry.RetryStrategy
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
    private val timeoutMs: Long = 30_000,
    private val rateLimitProvider: RateLimitProvider? = null,
    private val circuitBreaker: ProviderCircuitBreaker? = null
) {
    private val client: WebClient = WebClient.builder()
        .baseUrl(endpoint)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .build()

    open suspend fun execute(query: String, variables: JsonObject): JsonObject {
        if (apiKey.isNullOrBlank()) throw LinearError.MissingApiKey()
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            throw LinearError.CircuitOpen()
        }
        val body = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        return withContext(Dispatchers.IO) {
            rateLimitProvider?.waitForAvailability()
            try {
                val result = RetryStrategy.retryWithBackoff(
                    block = { _ ->
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
                    },
                    config = RetryConfig(),
                    shouldRetry = { it is LinearError.Request }
                )
                circuitBreaker?.recordSuccess()
                result
            } catch (e: LinearError) {
                circuitBreaker?.recordFailure()
                throw e
            } catch (e: Exception) {
                circuitBreaker?.recordFailure()
                throw LinearError.Request(e.message ?: "transport failure", e)
            }
        }
    }
}