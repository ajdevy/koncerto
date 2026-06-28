package com.flexsentlabs.koncerto.dashboard

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

@RestController
class OAuthCallbackController {

    private val webClient = WebClient.create()

    @GetMapping("/callback")
    fun handleCallback(@RequestParam params: Map<String, String>): Mono<ResponseEntity<String>> {
        val port = ApiV1Controller.getClaudeCallbackPort()
            ?: return Mono.just(
                ResponseEntity.status(503).body("No active Claude login session. Start login from the dashboard first.")
            )
        val uri = UriComponentsBuilder
            .fromUriString("http://localhost:$port/callback")
            .also { builder -> params.forEach { (k, v) -> builder.queryParam(k, v) } }
            .build()
            .toUri()

        return webClient.get()
            .uri(uri)
            .retrieve()
            .toEntity(String::class.java)
            .onErrorResume { ex ->
                Mono.just(
                    ResponseEntity.status(502).body("Claude callback failed: ${ex.message}")
                )
            }
    }
}
