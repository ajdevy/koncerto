package com.flexsentlabs.koncerto.agent

import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object AgentRuntimeTestSupport {

    suspend fun awaitUntil(
        timeoutMs: Long = 5_000,
        pollMs: Long = 20,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            delay(pollMs)
        }
    }

    fun collectEventsDuring(
        runtime: AgentRuntime,
        timeoutMs: Long = 5_000,
        until: (List<AgentEvent>) -> Boolean = { it.isNotEmpty() },
        action: suspend () -> Unit,
    ): List<AgentEvent> = runBlocking {
        val collected = Collections.synchronizedList(mutableListOf<AgentEvent>())
        val collector = launch {
            runtime.events().collect { collected += it }
        }
        delay(50)
        action()
        awaitUntil(timeoutMs) { until(collected) }
        delay(300)
        collector.cancel()
        collected.toList()
    }

    fun collectOutputDuring(
        runtime: AgentRuntime,
        timeoutMs: Long = 5_000,
        predicate: (List<String>) -> Boolean = { it.isNotEmpty() },
        action: suspend () -> Unit,
    ): List<String> = runBlocking {
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val collector = launch {
            runtime.output.collect { lines += it }
        }
        delay(50)
        action()
        awaitUntil(timeoutMs) { predicate(lines) }
        delay(300)
        collector.cancel()
        lines.toList()
    }
}
