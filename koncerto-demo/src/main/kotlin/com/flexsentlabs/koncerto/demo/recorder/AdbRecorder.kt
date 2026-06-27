package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdbRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.ADB

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val check = ProcessBuilder("adb", "devices").start()
            val checkCompleted = check.waitFor(5, TimeUnit.SECONDS)
            val output = if (checkCompleted) check.inputStream.bufferedReader().use { it.readText() } else ""
            checkCompleted && check.exitValue() == 0 && output.contains("device")
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("adb"))
                }

                val devicePath = "/sdcard/${outputFile.name}"
                val startTime = System.currentTimeMillis()

                val recordProcess = ProcessBuilder(
                    "adb", "shell", "screenrecord",
                    "--bit-rate", "2000000",
                    "--size", "${config.width}x${config.height}",
                    "--time-limit", config.maxDurationSeconds.toString(),
                    devicePath
                ).redirectErrorStream(true).start()

                val completed = recordProcess.waitFor(config.maxDurationSeconds + 30L, TimeUnit.SECONDS)
                if (!completed) {
                    recordProcess.destroyForcibly()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("adb screenrecord timed out"))
                    )
                }

                if (recordProcess.exitValue() != 0) {
                    val stderr = recordProcess.inputStream.bufferedReader().use { it.readText() }
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(
                            RuntimeException("adb screenrecord exited with code ${recordProcess.exitValue()}: $stderr")
                        )
                    )
                }

                val pullProcess = ProcessBuilder(
                    "adb", "pull", devicePath, outputFile.absolutePath
                ).redirectErrorStream(true).start()

                val pullCompleted = pullProcess.waitFor(30, TimeUnit.SECONDS)
                if (!pullCompleted || pullProcess.exitValue() != 0) {
                    pullProcess.destroyForcibly()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("adb pull failed"))
                    )
                }

                val deleteProcess = ProcessBuilder("adb", "shell", "rm", "-f", devicePath)
                    .redirectErrorStream(true).start()
                deleteProcess.waitFor(5, TimeUnit.SECONDS)

                val durationMs = System.currentTimeMillis() - startTime

                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = "mp4"
                ))
            } catch (e: Exception) {
                DemoResult.Failure(DemoError.RecordingFailed(e))
            }
        }
}
