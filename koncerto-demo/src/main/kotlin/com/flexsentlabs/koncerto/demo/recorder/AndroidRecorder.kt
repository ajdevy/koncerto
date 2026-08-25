package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import com.flexsentlabs.koncerto.demo.recorder.android.AdbCommands
import com.flexsentlabs.koncerto.demo.recorder.android.AndroidScenarioParser
import com.flexsentlabs.koncerto.demo.recorder.android.MaestroFlowBuilder
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records an Android demo: the ticket's scenario driven through the real app by Maestro while adb
 * captures the screen.
 *
 * This replaces a recorder that only ran `adb shell screenrecord` for a fixed duration — it captured
 * whatever happened to be on screen, launched nothing and drove nothing, so an Android ticket got a
 * video of an idle home screen. Here the app is installed, launched and exercised, and Maestro's
 * assertions decide the verdict: a flow whose expected screen never appears fails the recording, so
 * the pipeline's repair/escalation loop sees a broken feature instead of publishing a video of one.
 *
 * Device/OS plumbing, so excluded from coverage aggregation like the other recorders; the command
 * construction and output interpretation it delegates to [AdbCommands] are fully unit-tested.
 */
class AndroidRecorder(
    private val processStarter: ProcessStarter = { cmd, redirect ->
        ProcessBuilder(cmd).apply { if (redirect) redirectErrorStream(true) }.start()
    }
) : DemoRecorder {

    override val platform: DemoPlatform = DemoPlatform.ADB

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val devices = AdbCommands.parseDevices(runCapturing(listOf("adb", "devices"), 10) ?: return@runCatching false)
            AdbCommands.selectDevice(devices) != null && commandExists("maestro")
        }.getOrDefault(false)
    }

    override suspend fun record(
        config: RecordingConfig,
        outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> = withContext(Dispatchers.IO) {
        val devicesOutput = runCapturing(listOf("adb", "devices"), 15)
            ?: return@withContext failure("adb could not be run — the Android SDK platform tools are not on PATH")
        val devices = AdbCommands.parseDevices(devicesOutput)

        // Prerequisite gate. A missing or unauthorised device is not agent-fixable, so it surfaces
        // as a precise message the orchestrator turns into a blocking ticket comment rather than a
        // generic recording failure that would burn the self-healing retry budget.
        AdbCommands.unusableReason(devices)?.let { return@withContext unavailable(it) }
        if (!commandExists("maestro")) {
            return@withContext unavailable("maestro is not installed on the recording host")
        }
        val device = AdbCommands.selectDevice(devices)
            ?: return@withContext unavailable("no usable Android device")

        val scenario = config.scenarioPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { AndroidScenarioParser.parse(it.readText()) }
            ?: return@withContext failure(
                "no usable Android scenario: ${config.scenarioPath.ifBlank { "<none staged>" }} did not " +
                    "parse into a flow with an appId and at least one step"
            )

        val runDir = File(File(System.getProperty("user.home"), ".koncerto/android-recorder-tmp"), UUID.randomUUID().toString())
        runDir.mkdirs()
        val devicePath = "/sdcard/koncerto-demo-${UUID.randomUUID()}.mp4"
        try {
            val flowFile = File(runDir, "flow.yaml")
            flowFile.writeText(MaestroFlowBuilder.build(scenario))

            if (config.apkPath.isNotBlank()) {
                val install = runCapturing(AdbCommands.installCommand(device.serial, config.apkPath), 300)
                if (install == null || install.contains("Failure")) {
                    return@withContext failure("installing ${config.apkPath} failed: ${install?.takeLast(300) ?: "adb install timed out"}")
                }
            }

            val startTime = System.currentTimeMillis()
            val recordSeconds = config.maxDurationSeconds.coerceAtMost(AdbCommands.MAX_SCREENRECORD_SECONDS)
            val recorder = processStarter(
                AdbCommands.screenrecordCommand(device.serial, devicePath, config.width, config.height, recordSeconds),
                true
            )

            val maestro = runCapturingWithExit(
                AdbCommands.maestroCommand(device.serial, flowFile.absolutePath),
                recordSeconds.toLong() + GRACE_SECONDS
            )

            // Interrupt rather than kill so screenrecord finalises the MP4, then let it flush.
            runCapturing(AdbCommands.stopRecordingCommand(device.serial), 15)
            if (!recorder.waitFor(FLUSH_SECONDS, TimeUnit.SECONDS)) recorder.destroyForcibly()

            val pull = runCapturing(AdbCommands.pullCommand(device.serial, devicePath, outputFile.absolutePath), 120)
            runCapturing(AdbCommands.removeCommand(device.serial, devicePath), 15)

            val maestroFailure = maestro?.let { AdbCommands.failureReason(it.exitCode, it.output) }
                ?: "maestro did not finish within ${recordSeconds + GRACE_SECONDS}s"
            if (maestroFailure != null) {
                // Keep whatever was captured: a recording of the failing screen is exactly what the
                // repair loop and a human reviewer need to see.
                return@withContext failure(maestroFailure)
            }
            if (!outputFile.isFile || outputFile.length() == 0L) {
                return@withContext failure("screen recording produced no file: ${pull?.takeLast(200) ?: "adb pull failed"}")
            }

            DemoResult.Success(
                DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = System.currentTimeMillis() - startTime,
                    fileSizeBytes = outputFile.length(),
                    format = "mp4"
                )
            )
        } catch (e: Exception) {
            failure("Android recording error: ${e.message}")
        } finally {
            runCatching { runDir.deleteRecursively() }
        }
    }

    private data class Completed(val exitCode: Int, val output: String)

    private fun runCapturing(command: List<String>, timeoutSec: Long): String? =
        runCapturingWithExit(command, timeoutSec)?.output

    private fun runCapturingWithExit(command: List<String>, timeoutSec: Long): Completed? = try {
        val process = processStarter(command, true)
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            Completed(process.exitValue(), output)
        }
    } catch (_: Exception) {
        null
    }

    private fun commandExists(name: String): Boolean =
        runCapturingWithExit(listOf("which", name), 10)?.exitCode == 0

    private fun failure(message: String): DemoResult<DemoRecorder.RecordingResult> =
        DemoResult.Failure(DemoError.RecordingFailed(RuntimeException(message)))

    private fun unavailable(message: String): DemoResult<DemoRecorder.RecordingResult> =
        DemoResult.Failure(DemoError.RecorderNotAvailable(message))

    private companion object {
        const val GRACE_SECONDS = 60L
        const val FLUSH_SECONDS = 20L
    }
}
