package com.flexsentlabs.koncerto.demo.observability

import com.flexsentlabs.koncerto.demo.model.DemoStatus

class DemoMetricsRecorder {
    private val recordingAttempts = mutableMapOf<String, Int>()
    private val recordingDuration = mutableListOf<Long>()
    private val recordingErrors = mutableMapOf<String, Int>()
    private val storageUploads = mutableMapOf<String, Int>()
    private val storageErrors = mutableMapOf<String, Int>()

    fun recordAttempt(platform: String, status: DemoStatus, durationMs: Long) {
        recordingAttempts.merge(platform, 1, Int::plus)
        recordingDuration.add(durationMs)
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
        averageDurationMs = recordingDuration.average().toLong(),
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
