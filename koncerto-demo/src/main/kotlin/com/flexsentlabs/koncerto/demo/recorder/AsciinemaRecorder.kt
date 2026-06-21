package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AsciinemaRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.ASCIINEMA

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("which", "asciinema").start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("asciinema"))
                }

                val startTime = System.currentTimeMillis()

                val process = ProcessBuilder(
                    "asciinema", "rec",
                    "--overwrite",
                    "--title", "Koncerto demo recording",
                    outputFile.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(config.maxDurationSeconds.toLong(), TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("asciinema recording timed out"))
                    )
                }

                val exitCode = process.exitValue()
                val durationMs = System.currentTimeMillis() - startTime

                if (exitCode != 0) {
                    val stderr = process.inputStream.bufferedReader().readText()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(
                            RuntimeException("asciinema exited with code $exitCode: $stderr")
                        )
                    )
                }

                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = "cast"
                ))
            } catch (e: Exception) {
                DemoResult.Failure(DemoError.RecordingFailed(e))
            }
        }
}
