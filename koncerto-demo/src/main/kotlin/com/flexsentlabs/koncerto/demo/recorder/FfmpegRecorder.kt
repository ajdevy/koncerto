package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FfmpegRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.FFMPEG

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder("ffmpeg", "-version").start()
            process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("ffmpeg"))
                }

                val startTime = System.currentTimeMillis()
                val inputArgs = detectInputArgs(config)
                val filterComplex = buildFilterComplex(config)

                val args = mutableListOf(
                    "ffmpeg", "-y"
                )
                args.addAll(inputArgs)
                args.addAll(listOf(
                    "-vf", filterComplex,
                    "-c:v", "libvpx-vp9",
                    "-b:v", "200k",
                    "-s", "${config.width}x${config.height}",
                    "-t", config.maxDurationSeconds.toString(),
                    outputFile.absolutePath
                ))

                val process = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(config.maxDurationSeconds + 30L, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("ffmpeg recording timed out"))
                    )
                }

                val exitCode = process.exitValue()
                val durationMs = System.currentTimeMillis() - startTime

                if (exitCode != 0) {
                    val stderr = process.inputStream.bufferedReader().readText()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(
                            RuntimeException("ffmpeg exited with code $exitCode: $stderr")
                        )
                    )
                }

                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = config.outputFormat
                ))
            } catch (e: Exception) {
                DemoResult.Failure(DemoError.RecordingFailed(e))
            }
        }

    private fun detectInputArgs(config: RecordingConfig): List<String> {
        val osName = System.getProperty("os.name").lowercase()
        return when {
            osName.contains("mac") -> listOf("-f", "avfoundation", "-i", config.captureInputIndex)
            osName.contains("linux") -> listOf("-f", "x11grab", "-i", config.captureInputIndex)
            osName.contains("win") -> listOf("-f", "gdigrab", "-i", config.captureInputIndex)
            else -> listOf("-f", "avfoundation", "-i", "1")
        }
    }

    private fun buildFilterComplex(config: RecordingConfig): String = buildString {
        append("fps=${config.frameRate}")
        if (config.timestampOverlay) {
            append(",drawtext=text='%{localtime\\:%T}':fontcolor=white:fontsize=24:x=10:y=10")
        }
    }
}
