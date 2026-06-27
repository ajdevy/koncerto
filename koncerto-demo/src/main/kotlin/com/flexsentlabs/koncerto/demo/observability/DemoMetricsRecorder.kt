package com.flexsentlabs.koncerto.demo.observability

import com.flexsentlabs.koncerto.demo.model.DemoStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class DemoMetricsRecorder {
    private val recordingAttempts = ConcurrentHashMap<String, Int>()
    private val recordingErrors = ConcurrentHashMap<String, Int>()
    private val storageUploads = ConcurrentHashMap<String, Int>()
    private val storageErrors = ConcurrentHashMap<String, Int>()
    private val recordingDurationTotal = AtomicLong(0L)
    private val recordingDurationCount = AtomicLong(0L)

    fun recordAttempt(platform: String, status: DemoStatus, durationMs: Long) {
        recordingAttempts.merge(platform, 1, Int::plus)
        recordingDurationTotal.addAndGet(durationMs)
        recordingDurationCount.incrementAndGet()
        if (status == DemoStatus.FAILED) {
            recordingErrors.merge(platform, 1, Int::plus)
        }
    }

    fun recordStorageResult(success: Boolean) {
        if (success) {
            storageUploads.merge("upload", 1, Int::plus)
        } else {
            storageErrors.merge("upload", 1, Int::plus)
        }
    }

    fun snapshot(): MetricsSnapshot = MetricsSnapshot(
        attemptsByPlatform = recordingAttempts.toMap(),
        errorsByPlatform = recordingErrors.toMap(),
        averageDurationMs = if (recordingDurationCount.get() > 0)
            recordingDurationTotal.get() / recordingDurationCount.get()
        else 0L,
        storageUploadCount = storageUploads.values.sum(),
        storageErrorCount = storageErrors.values.sum()
    )

    data class MetricsSnapshot(
        val attemptsByPlatform: Map<String, Int>,
        val errorsByPlatform: Map<String, Int>,
        val averageDurationMs: Long,
        val storageUploadCount: Int,
        val storageErrorCount: Int
    )
}
