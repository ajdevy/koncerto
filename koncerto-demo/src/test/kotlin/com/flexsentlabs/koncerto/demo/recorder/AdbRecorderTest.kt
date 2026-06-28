package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AdbRecorderTest {

    private val config = RecordingConfig(
        platform = DemoPlatform.ADB,
        width = 720,
        height = 1280,
        maxDurationSeconds = 5
    )

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `isAvailable returns true when adb devices lists a device`() = runTest {
        val process = mockProcess(exitCode = 0, completed = true, stdout = "List of devices attached\nemulator-5554\tdevice\n")
        val recorder = AdbRecorder { cmd, _ ->
            assert(cmd == listOf("adb", "devices"))
            process
        }

        assert(recorder.isAvailable())
    }

    @Test
    fun `isAvailable returns false when no device connected`() = runTest {
        val process = mockProcess(exitCode = 0, completed = true, stdout = "List of devices attached\n")
        val recorder = AdbRecorder { _, _ -> process }

        assert(!recorder.isAvailable())
    }

    @Test
    fun `record succeeds through screenrecord pull and cleanup`() = runTest {
        val outputFile = File(tempDir, "demo.mp4")
        val devices = mockProcess(exitCode = 0, completed = true, stdout = "emulator-5554\tdevice\n")
        val screenrecord = mockProcess(exitCode = 0, completed = true)
        val pull = mockProcess(exitCode = 0, completed = true)
        val cleanup = mockProcess(exitCode = 0, completed = true)
        var call = 0
        val recorder = AdbRecorder { cmd, redirect ->
            call++
            when {
                cmd.contains("devices") -> devices
                cmd.contains("screenrecord") -> {
                    assert(cmd.contains("--size"))
                    assert(redirect)
                    screenrecord
                }
                cmd.contains("pull") -> {
                    outputFile.writeText("mp4 data")
                    pull
                }
                cmd.contains("rm") -> cleanup
                else -> error("unexpected command: $cmd")
            }
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Success)
        val recording = (result as DemoResult.Success).value
        assert(recording.format == "mp4")
        assert(recording.fileSizeBytes > 0)
        assert(call == 4)
    }

    @Test
    fun `record returns failure when adb unavailable`() = runTest {
        val outputFile = File(tempDir, "demo.mp4")
        val noDevice = mockProcess(exitCode = 0, completed = true, stdout = "")
        val recorder = AdbRecorder { _, _ -> noDevice }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.RecorderNotAvailable)
    }

    @Test
    fun `record returns failure when screenrecord times out`() = runTest {
        val outputFile = File(tempDir, "demo.mp4")
        val devices = mockProcess(exitCode = 0, completed = true, stdout = "emulator-5554\tdevice\n")
        val hanging = mockProcess(exitCode = 0, completed = false)
        var call = 0
        val recorder = AdbRecorder { cmd, _ ->
            call++
            if (cmd.contains("devices")) devices else hanging
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error as DemoError.RecordingFailed
        assert(error.message!!.contains("timed out"))
    }

    @Test
    fun `record returns failure when adb pull fails`() = runTest {
        val outputFile = File(tempDir, "demo.mp4")
        val devices = mockProcess(exitCode = 0, completed = true, stdout = "emulator-5554\tdevice\n")
        val screenrecord = mockProcess(exitCode = 0, completed = true)
        val failedPull = mockProcess(exitCode = 1, completed = true, stdout = "pull failed")
        var call = 0
        val recorder = AdbRecorder { cmd, _ ->
            call++
            when {
                cmd.contains("devices") -> devices
                cmd.contains("screenrecord") -> screenrecord
                else -> failedPull
            }
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error as DemoError.RecordingFailed
        assert(error.message!!.contains("pull failed"))
    }

    private fun mockProcess(
        exitCode: Int,
        completed: Boolean,
        stdout: String = ""
    ) = mockk<Process> {
        every { waitFor(any(), any()) } returns completed
        every { waitFor(any<Long>(), any()) } returns completed
        every { exitValue() } returns exitCode
        every { inputStream } returns ByteArrayInputStream(stdout.toByteArray())
        every { destroyForcibly() } returns this
    }
}
