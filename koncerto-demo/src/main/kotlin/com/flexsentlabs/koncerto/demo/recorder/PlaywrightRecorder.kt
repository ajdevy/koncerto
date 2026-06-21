package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaywrightRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val which = ProcessBuilder("which", "node").start()
            which.waitFor(5, TimeUnit.SECONDS) && which.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("playwright"))
                }

                val startTime = System.currentTimeMillis()
                val videoDir = File(System.getProperty("java.io.tmpdir"), "pw-video-${startTime}")
                videoDir.mkdirs()
                videoDir.deleteOnExit()

                val scriptContent = buildPlaywrightScript(config, videoDir.absolutePath)
                val scriptFile = File.createTempFile("pw-record-", ".mjs")
                scriptFile.writeText(scriptContent)
                scriptFile.deleteOnExit()

                val process = ProcessBuilder("node", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                val completed = process.waitFor(config.maxDurationSeconds + 30L, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroyForcibly()
                    scriptFile.delete()
                    videoDir.deleteRecursively()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Playwright recording timed out"))
                    )
                }

                val exitCode = process.exitValue()
                val durationMs = System.currentTimeMillis() - startTime
                scriptFile.delete()

                if (exitCode != 0) {
                    val stderr = process.inputStream.bufferedReader().readText()
                    videoDir.deleteRecursively()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(
                            RuntimeException("Playwright exited with code $exitCode: $stderr")
                        )
                    )
                }

                val videoFile = findVideoFile(videoDir)
                if (videoFile != null && videoFile.exists()) {
                    videoFile.renameTo(outputFile)
                }
                videoDir.deleteRecursively()

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

    private fun buildPlaywrightScript(config: RecordingConfig, videoDir: String): String = """
import { chromium } from 'playwright';
(async () => {
  const browser = await chromium.launch({ headless: false });
  const context = await browser.newContext({
    viewport: { width: ${config.width}, height: ${config.height} },
    recordVideo: { dir: '${videoDir}' }
  });
  const page = await context.newPage();
  ${if (config.targetUrl.isNotBlank()) "await page.goto('${config.targetUrl}', { waitUntil: 'networkidle' });" else ""}
  await page.waitForTimeout(${config.maxDurationSeconds * 1000});
  await context.close();
  await browser.close();
})();
""".trimIndent()

    private fun findVideoFile(dir: File): File? {
        return dir.listFiles { f -> f.name.endsWith(".webm") }?.maxByOrNull { f -> f.lastModified() }
    }
}
