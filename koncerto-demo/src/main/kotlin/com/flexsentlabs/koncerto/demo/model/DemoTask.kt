package com.flexsentlabs.koncerto.demo.model

import kotlinx.serialization.Serializable

@Serializable
data class DemoTask(
    val id: String,
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String?,
    val platform: DemoPlatform,
    val status: DemoStatus,
    val recordingUrl: String? = null,
    val storageKey: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val trigger: DemoTrigger,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String? = null,
    val isKept: Boolean = false,
    val metadata: String? = null,
    val htmlReportKey: String? = null,
    val fallbackFrom: String? = null
)

@Serializable
enum class DemoPlatform {
    PLAYWRIGHT, ASCIINEMA, ADB, XCRUN, FFMPEG
}

@Serializable
enum class DemoStatus {
    PENDING, RECORDING, ENCODING, UPLOADING, COMPLETED, FAILED, PARTIAL
}

@Serializable
enum class DemoTrigger {
    MANUAL, REVIEW_PASSED, SCHEDULED
}
