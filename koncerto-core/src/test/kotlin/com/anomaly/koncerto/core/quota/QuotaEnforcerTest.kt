package com.anomaly.koncerto.core.quota

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Test

class QuotaEnforcerTest {

    private val enforcer = QuotaEnforcer()
    private val config = QuotaConfig(maxConcurrentAgents = 3, maxRateLimit = 100, maxWorkspaceStorageMB = 1024)

    @Test
    fun `acquire within limit returns true`() {
        val result = enforcer.tryAcquire("project-a", config)
        assertThat(result).isTrue()
        assertThat(enforcer.getActiveCount("project-a")).isEqualTo(1)
    }

    @Test
    fun `acquire over limit returns false`() {
        val tightConfig = QuotaConfig(maxConcurrentAgents = 1)
        assertThat(enforcer.tryAcquire("project-b", tightConfig)).isTrue()
        assertThat(enforcer.tryAcquire("project-b", tightConfig)).isFalse()
        assertThat(enforcer.getActiveCount("project-b")).isEqualTo(1)
    }

    @Test
    fun `release decrements counter`() {
        assertThat(enforcer.tryAcquire("project-c", config)).isTrue()
        assertThat(enforcer.getActiveCount("project-c")).isEqualTo(1)
        enforcer.release("project-c")
        assertThat(enforcer.getActiveCount("project-c")).isEqualTo(0)
    }

    @Test
    fun `concurrent acquire from multiple threads`() {
        val threadCount = 10
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()

        val tightConfig = QuotaConfig(maxConcurrentAgents = 3)

        for (i in 0 until threadCount) {
            executor.submit {
                val acquired = enforcer.tryAcquire("project-d", tightConfig)
                results.add(acquired)
                latch.countDown()
            }
        }

        latch.await(5, TimeUnit.SECONDS)
        executor.shutdown()

        val acquiredCount = results.count { it }
        val failedCount = results.count { !it }

        assertThat(acquiredCount).isEqualTo(3)
        assertThat(failedCount).isEqualTo(7)
        assertThat(enforcer.getActiveCount("project-d")).isEqualTo(3)
    }

    @Test
    fun `unlimited config allows all`() {
        val unlimited = QuotaConfig(maxConcurrentAgents = -1)
        val enforcer = QuotaEnforcer()
        for (i in 0 until 100) {
            assertThat(enforcer.tryAcquire("project-e", unlimited)).isTrue()
        }
        assertThat(enforcer.getRemaining("project-e", unlimited)).isEqualTo(Int.MAX_VALUE)
    }

    @Test
    fun `getRemaining returns ceiling minus active`() {
        val tightConfig = QuotaConfig(maxConcurrentAgents = 5)
        val enforcer = QuotaEnforcer()
        assertThat(enforcer.getRemaining("project-f", tightConfig)).isEqualTo(5)
        enforcer.tryAcquire("project-f", tightConfig)
        assertThat(enforcer.getRemaining("project-f", tightConfig)).isEqualTo(4)
        enforcer.tryAcquire("project-f", tightConfig)
        assertThat(enforcer.getRemaining("project-f", tightConfig)).isEqualTo(3)
    }
}
