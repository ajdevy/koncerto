package com.flexsentlabs.koncerto.core.agent

data class FallbackProviderConfig(
    val primaryProvider: String,
    val fallbackProviders: List<String> = emptyList(),
    val fallbackOnFailure: Boolean = true,
    val fallbackOnTimeout: Boolean = true
)

object FallbackProvider {
    suspend fun <T> withFallback(
        primary: suspend () -> T,
        fallbacks: List<suspend () -> T>,
        onResult: (T) -> Boolean = { true }
    ): Result<T> {
        val providers = listOf(primary) + fallbacks
        for ((index, provider) in providers.withIndex()) {
            try {
                val result = provider()
                if (onResult(result)) return Result.success(result)
            } catch (e: Exception) {
                if (index == providers.lastIndex) return Result.failure(e)
            }
        }
        return Result.failure(RuntimeException("no_providers_available"))
    }
}
