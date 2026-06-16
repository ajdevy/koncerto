package com.flexsentlabs.koncerto.dashboard

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class DashboardController {
    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun dashboard(): Mono<String> = Mono.just(
        ClassPathResource("templates/dashboard.html").inputStream.bufferedReader().readText()
    )
}
