package com.flexsentlabs.koncerto.core.circuitbreaker

import java.util.concurrent.ConcurrentHashMap

object CircuitBreakerRegistry {
    private val breakers = ConcurrentHashMap<String, ProviderCircuitBreaker>()

    fun getOrCreate(key: String, config: CircuitBreakerConfig = CircuitBreakerConfig()): ProviderCircuitBreaker {
        return breakers.getOrPut(key) { ProviderCircuitBreaker(config) }
    }

    fun get(key: String): ProviderCircuitBreaker? = breakers[key]

    fun getAll(): Map<String, ProviderCircuitBreaker> = breakers.toMap()

    fun reset(key: String) { breakers.remove(key) }

    fun resetAll() { breakers.clear() }
}
