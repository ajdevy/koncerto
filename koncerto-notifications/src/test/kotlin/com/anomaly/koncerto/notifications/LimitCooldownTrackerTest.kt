package com.anomaly.koncerto.notifications

import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class LimitCooldownTrackerTest {

    @Test
    fun `first send is always allowed`() {
        val tracker = LimitCooldownTracker(cooldownMs = 60_000)
        assertThat(tracker.shouldSend("RateLimitError", "issue-1")).isTrue()
    }

    @Test
    fun `second send within cooldown is blocked`() {
        val tracker = LimitCooldownTracker(cooldownMs = 60_000)
        tracker.shouldSend("RateLimitError", "issue-1")
        assertThat(tracker.shouldSend("RateLimitError", "issue-1")).isFalse()
    }

    @Test
    fun `different issue ids are independent`() {
        val tracker = LimitCooldownTracker(cooldownMs = 60_000)
        tracker.shouldSend("RateLimitError", "issue-1")
        assertThat(tracker.shouldSend("RateLimitError", "issue-2")).isTrue()
    }

    @Test
    fun `different error types for same issue are independent`() {
        val tracker = LimitCooldownTracker(cooldownMs = 60_000)
        tracker.shouldSend("RateLimitError", "issue-1")
        assertThat(tracker.shouldSend("AuthError", "issue-1")).isTrue()
    }

    @Test
    fun `cooldown resets after timeout`() {
        val tracker = LimitCooldownTracker(cooldownMs = 50)
        tracker.shouldSend("RateLimitError", "issue-1")
        Thread.sleep(100)
        assertThat(tracker.shouldSend("RateLimitError", "issue-1")).isTrue()
    }

    @Test
    fun `reset clears all cooldowns`() {
        val tracker = LimitCooldownTracker(cooldownMs = 60_000)
        tracker.shouldSend("RateLimitError", "issue-1")
        tracker.reset()
        assertThat(tracker.shouldSend("RateLimitError", "issue-1")).isTrue()
    }
}
