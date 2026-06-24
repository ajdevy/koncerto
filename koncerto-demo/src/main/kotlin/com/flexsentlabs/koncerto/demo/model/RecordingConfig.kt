package com.flexsentlabs.koncerto.demo.model

import kotlin.time.Duration.Companion.seconds

data class RecordingConfig(
    val platform: DemoPlatform,
    val width: Int = 1280,
    val height: Int = 720,
    val frameRate: Int = 10,
    val maxDurationSeconds: Int = 120,
    val timestampOverlay: Boolean = true,
    val codec: String = "vp9",
    val outputFormat: String = "webm",
    val targetUrl: String = "",
    val captureInputIndex: String = "1",
    val scenarioPath: String = ""
) {
    val maxDuration: kotlin.time.Duration get() = maxDurationSeconds.seconds
}
