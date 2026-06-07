package com.anomaly.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class RuntimeStateTest {

    @Test
    fun `available slots is max minus running`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 3
        assertThat(s.availableSlots()).isEqualTo(3)
        s.maxConcurrentAgents = 1
        assertThat(s.availableSlots()).isEqualTo(1)
    }

    @Test
    fun `available slots never negative`() {
        val s = RuntimeState()
        s.maxConcurrentAgents = 0
        assertThat(s.availableSlots()).isEqualTo(0)
    }
}
