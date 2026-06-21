package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File

interface DemoRecorder {
    val platform: DemoPlatform

    suspend fun isAvailable(): Boolean
    suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<RecordingResult>

    data class RecordingResult(
        val file: File,
        val durationMs: Long,
        val fileSizeBytes: Long,
        val format: String
    )
}
