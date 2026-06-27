package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class XcrunRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.XCRUN

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("xcrun", "--version").start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("xcrun"))
                }

                val startTime = System.currentTimeMillis()

                val process = ProcessBuilder(
                    "xcrun", "simctl", "io", "booted",
                    "recordVideo",
                    "--codec", "h264",
                    "--force",
                    outputFile.absolutePath
                ).redirectErrorStream(true).start()

                val completed = process.waitFor(config.maxDurationSeconds.toLong(), TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                }

                val exitCode = process.exitValue()
                val durationMs = System.currentTimeMillis() - startTime

                if (exitCode != 0) {
                    val stderr = process.inputStream.bufferedReader().use { it.readText() }
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(
                            RuntimeException("xcrun simctl exited with code $exitCode: $stderr")
                        )
                    )
                }

                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = "mov"
                ))
            } catch (e: Exception) {
                DemoResult.Failure(DemoError.RecordingFailed(e))
            }
        }
}
