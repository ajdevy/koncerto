package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class RetryExecutorTest {

    private val executor = RetryExecutor(300_000L)

    @Test
    fun `first attempt delay is 10 seconds`() {
        val delay = executor.computeDelay(0)
        assertThat(delay).isEqualTo(10_000L)
    }

    @Test
    fun `second attempt delay is 20 seconds`() {
        val delay = executor.computeDelay(1)
        assertThat(delay).isEqualTo(20_000L)
    }

    @Test
    fun `delay doubles each attempt`() {
        assertThat(executor.computeDelay(0)).isEqualTo(10_000L)
        assertThat(executor.computeDelay(1)).isEqualTo(20_000L)
        assertThat(executor.computeDelay(2)).isEqualTo(40_000L)
        assertThat(executor.computeDelay(3)).isEqualTo(80_000L)
    }

    @Test
    fun `delay is capped at maxBackoffMs`() {
        val cap = RetryExecutor(60_000L)
        // Attempt 4: 10_000 * 2^4 = 160_000, capped at 60_000
        assertThat(cap.computeDelay(4)).isEqualTo(60_000L)
    }

    @Test
    fun `delay does not overflow on very high attempts`() {
        val delay = executor.computeDelay(50)
        assertThat(delay <= 300_000L).isTrue()
        assertThat(delay).isEqualTo(300_000L)
    }

    @Test
    fun `createEntry returns entry with correct attempt and delay`() {
        val beforeMs = System.currentTimeMillis()
        val entry = executor.createEntry("1", "A-1", 0, "timeout")

        assertThat(entry.attempt).isEqualTo(1)
        assertThat(entry.error).isEqualTo("timeout")
        assertThat(entry.identifier).isEqualTo("A-1")
        assertThat(entry.dueAtMs >= beforeMs + 10_000).isTrue()
    }

    @Test
    fun `createEntry increments attempt`() {
        val first = executor.createEntry("1", "A-1", 0, "e1")
        val second = executor.createEntry("1", "A-1", 1, "e2")

        assertThat(first.attempt).isEqualTo(1)
        assertThat(second.attempt).isEqualTo(2)
    }
}
