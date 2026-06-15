package com.anomaly.koncerto.dashboard

import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import java.time.Duration

@RestController
@RequestMapping("/api/v1")
class TunnelController(
    private val ngrokApiUrl: String = "http://ngrok:4040"
) {
    @Serializable
    data class TunnelResponse(
        val url: String?,
        val status: String
    )

    @Serializable
    data class NgrokTunnelsResponse(
        val tunnels: List<NgrokTunnel> = emptyList()
    )

    @Serializable
    data class NgrokTunnel(
        val public_url: String? = null,
        val config: NgrokConfig? = null
    )

    @Serializable
    data class NgrokConfig(
        val addr: String? = null
    )

    private val client = WebClient.create()

    @GetMapping("/tunnel", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getTunnel(): Mono<TunnelResponse> {
        return client.get()
            .uri("$ngrokApiUrl/api/tunnels")
            .retrieve()
            .bodyToMono(NgrokTunnelsResponse::class.java)
            .map { response ->
                val tunnel = response.tunnels.firstOrNull { t ->
                    t.config?.addr?.contains("17348") == true
                }
                TunnelResponse(
                    url = tunnel?.public_url,
                    status = if (tunnel?.public_url != null) "active" else "inactive"
                )
            }
            .onErrorResume {
                Mono.just(TunnelResponse(url = null, status = "inactive"))
            }
    }
}
