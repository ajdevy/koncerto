package com.flexsentlabs.koncerto.core.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFailure
import assertk.assertions.isFalse
import assertk.assertions.isSuccess
import assertk.assertions.isTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ProviderFallbackTest {

    @Test
    fun `primary succeeds returns success`() = runBlocking {
        val result = FallbackProvider.withFallback(
            primary = { "primary" },
            fallbacks = emptyList()
        )
        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo("primary")
    }

    @Test
    fun `primary fails fallback succeeds returns fallback result`() = runBlocking {
        var fallbackCalled = false
        val result = FallbackProvider.withFallback(
            primary = { throw RuntimeException("primary failed") },
            fallbacks = listOf<suspend () -> String>({
                fallbackCalled = true
                "fallback"
            })
        )
        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo("fallback")
        assertThat(fallbackCalled).isTrue()
    }

    @Test
    fun `primary fails no fallbacks returns failure`() = runBlocking {
        val result = FallbackProvider.withFallback(
            primary = { throw RuntimeException("primary failed") },
            fallbacks = emptyList()
        )
        assertThat(result).isFailure()
    }

    @Test
    fun `all providers fail returns last failure`() = runBlocking {
        val result = FallbackProvider.withFallback(
            primary = { throw RuntimeException("primary failed") },
            fallbacks = listOf<suspend () -> String>(
                { throw RuntimeException("fallback1 failed") },
                { throw RuntimeException("fallback2 failed") }
            )
        )
        assertThat(result).isFailure()
    }

    @Test
    fun `onResult filter can trigger fallback`() = runBlocking {
        var fallbackCalled = false
        val result = FallbackProvider.withFallback(
            primary = { "unacceptable" },
            fallbacks = listOf<suspend () -> String>({
                fallbackCalled = true
                "acceptable"
            }),
            onResult = { s: String -> s != "unacceptable" }
        )
        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo("acceptable")
        assertThat(fallbackCalled).isTrue()
    }

    @Test
    fun `onResult filter with acceptable result uses primary`() = runBlocking {
        var fallbackCalled = false
        val result = FallbackProvider.withFallback(
            primary = { "good-result" },
            fallbacks = listOf<suspend () -> String>({
                fallbackCalled = true
                "bad"
            }),
            onResult = { s: String -> s.startsWith("good") }
        )
        assertThat(result).isSuccess()
        assertThat(result.getOrNull()).isEqualTo("good-result")
        assertThat(fallbackCalled).isFalse()
    }

    @Test
    fun `all providers filtered out returns failure`() = runBlocking {
        val result = FallbackProvider.withFallback(
            primary = { "bad" },
            fallbacks = listOf<suspend () -> String>({ "also bad" }),
            onResult = { false }
        )
        assertThat(result).isFailure()
    }
}
