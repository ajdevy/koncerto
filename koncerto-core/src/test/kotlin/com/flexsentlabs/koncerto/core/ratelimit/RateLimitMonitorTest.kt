package com.flexsentlabs.koncerto.core.ratelimit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test

class RateLimitMonitorTest {

    @Test
    fun `checkAndAlert triggers when minute usage exceeds threshold`() {
        val monitor = DefaultRateLimitMonitor()
        val stats = RateLimitStats(availableTokens = 10, requestsLastMinute = 80, requestsLastHour = 10, limitPerMinute = 100, limitPerHour = 1000)
        val alert = monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(alert).isNotNull()
        assertThat(alert!!.limitType).isEqualTo("minute")
        assertThat(alert.currentUsage).isEqualTo(80)
        assertThat(alert.limit).isEqualTo(100)
    }

    @Test
    fun `checkAndAlert triggers when hour usage exceeds threshold`() {
        val monitor = DefaultRateLimitMonitor()
        val stats = RateLimitStats(availableTokens = 10, requestsLastMinute = 5, requestsLastHour = 900, limitPerMinute = 100, limitPerHour = 1000)
        val alert = monitor.checkAndAlert(stats, "test-provider", 80)
        assertThat(alert).isNotNull()
        assertThat(alert!!.limitType).isEqualTo("hour")
        assertThat(alert.currentUsage).isEqualTo(900)
        assertThat(alert.limit).isEqualTo(1000)
    }

    @Test
    fun `checkAndAlert returns null when under threshold`() {
        val monitor = DefaultRateLimitMonitor()
        val stats = RateLimitStats(availableTokens = 10, requestsLastMinute = 10, requestsLastHour = 50, limitPerMinute = 100, limitPerHour = 1000)
        val alert = monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(alert).isNull()
    }

    @Test
    fun `acknowledgeAlert suppresses duplicates`() {
        val monitor = DefaultRateLimitMonitor()
        val stats = RateLimitStats(availableTokens = 10, requestsLastMinute = 80, requestsLastHour = 10, limitPerMinute = 100, limitPerHour = 1000)
        val firstAlert = monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(firstAlert).isNotNull()
        val secondAlert = monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(secondAlert).isNotNull()
        assertThat(monitor.getRecentAlerts().size).isEqualTo(1)
        monitor.acknowledgeAlert("test-provider", "minute")
        val thirdAlert = monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(thirdAlert).isNotNull()
        assertThat(monitor.getRecentAlerts().size).isEqualTo(2)
    }

    @Test
    fun `clearAlerts removes all alerts`() {
        val monitor = DefaultRateLimitMonitor()
        val stats = RateLimitStats(availableTokens = 10, requestsLastMinute = 80, requestsLastHour = 10, limitPerMinute = 100, limitPerHour = 1000)
        monitor.checkAndAlert(stats, "test-provider", 75)
        assertThat(monitor.getRecentAlerts().isEmpty()).isEqualTo(false)
        monitor.clearAlerts()
        assertThat(monitor.getRecentAlerts().isEmpty()).isEqualTo(true)
    }
}
