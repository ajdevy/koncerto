package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import org.junit.jupiter.api.Test

class DemoMetricsRecorderTest {

    @Test
    fun `recordAttempt increments counters`() {
        val metrics = DemoMetricsRecorder()

        metrics.recordAttempt("playwright", DemoStatus.COMPLETED, 1000L)
        metrics.recordAttempt("playwright", DemoStatus.COMPLETED, 2000L)
        metrics.recordAttempt("asciinema", DemoStatus.FAILED, 500L)

        val snapshot = metrics.snapshot()
        assert(snapshot.attemptsByPlatform["playwright"] == 2)
        assert(snapshot.attemptsByPlatform["asciinema"] == 1)
        assert(snapshot.errorsByPlatform["asciinema"] == 1)
    }

    @Test
    fun `recordStorageResult increments counters`() {
        val metrics = DemoMetricsRecorder()

        metrics.recordStorageResult(true)
        metrics.recordStorageResult(true)
        metrics.recordStorageResult(false)

        val snapshot = metrics.snapshot()
        assert(snapshot.storageUploadCount == 2)
        assert(snapshot.storageErrorCount == 1)
    }

    @Test
    fun `averageDurationMs is zero for no recordings`() {
        val metrics = DemoMetricsRecorder()
        val snapshot = metrics.snapshot()
        assert(snapshot.averageDurationMs == 0L)
    }

    @Test
    fun `averageDurationMs computes correctly`() {
        val metrics = DemoMetricsRecorder()

        metrics.recordAttempt("test", DemoStatus.COMPLETED, 1000L)
        metrics.recordAttempt("test", DemoStatus.COMPLETED, 2000L)
        metrics.recordAttempt("test", DemoStatus.COMPLETED, 3000L)

        val snapshot = metrics.snapshot()
        assert(snapshot.averageDurationMs == 2000L)
    }
}
