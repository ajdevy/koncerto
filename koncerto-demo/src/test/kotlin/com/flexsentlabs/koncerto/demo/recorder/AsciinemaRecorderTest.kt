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

class AsciinemaRecorderTest {

    private val config = RecordingConfig(
        platform = DemoPlatform.ASCIINEMA,
        maxDurationSeconds = 5
    )

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `isAvailable returns true when which asciinema succeeds`() = runTest {
        val process = mockProcess(exitCode = 0, completed = true)
        val recorder = AsciinemaRecorder { cmd, _ ->
            assert(cmd == listOf("which", "asciinema"))
            process
        }

        assert(recorder.isAvailable())
    }

    @Test
    fun `isAvailable returns false when which asciinema fails`() = runTest {
        val process = mockProcess(exitCode = 1, completed = true)
        val recorder = AsciinemaRecorder { _, _ -> process }

        assert(!recorder.isAvailable())
    }

    @Test
    fun `record succeeds when asciinema exits cleanly`() = runTest {
        val outputFile = File(tempDir, "demo.cast")
        val availability = mockProcess(exitCode = 0, completed = true)
        val record = mockProcess(exitCode = 0, completed = true)
        var call = 0
        val recorder = AsciinemaRecorder { cmd, redirect ->
            call++
            when (call) {
                1 -> {
                    assert(cmd.first() == "which")
                    availability
                }
                else -> {
                    assert(cmd.first() == "asciinema")
                    assert(redirect)
                    outputFile.writeText("cast data")
                    record
                }
            }
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Success)
        val recording = (result as DemoResult.Success).value
        assert(recording.format == "cast")
        assert(recording.fileSizeBytes > 0)
    }

    @Test
    fun `record returns failure when recorder unavailable`() = runTest {
        val outputFile = File(tempDir, "demo.cast")
        val unavailable = mockProcess(exitCode = 1, completed = true)
        val recorder = AsciinemaRecorder { _, _ -> unavailable }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.RecorderNotAvailable)
    }

    @Test
    fun `record returns failure on timeout`() = runTest {
        val outputFile = File(tempDir, "demo.cast")
        val availability = mockProcess(exitCode = 0, completed = true)
        val hanging = mockProcess(exitCode = 0, completed = false)
        var call = 0
        val recorder = AsciinemaRecorder { cmd, _ ->
            call++
            if (cmd.first() == "which") availability else hanging
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error
        assert(error is DemoError.RecordingFailed)
        assert(error.message!!.contains("timed out"))
    }

    @Test
    fun `record returns failure on non-zero exit`() = runTest {
        val outputFile = File(tempDir, "demo.cast")
        val availability = mockProcess(exitCode = 0, completed = true)
        val failed = mockProcess(exitCode = 2, completed = true, stderr = "rec failed")
        var call = 0
        val recorder = AsciinemaRecorder { cmd, _ ->
            call++
            if (cmd.first() == "which") availability else failed
        }

        val result = recorder.record(config, outputFile)

        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error as DemoError.RecordingFailed
        assert(error.message!!.contains("rec failed"))
    }

    private fun mockProcess(
        exitCode: Int,
        completed: Boolean,
        stderr: String = ""
    ) = mockk<Process> {
        every { waitFor(any(), any()) } returns completed
        every { waitFor(any<Long>(), any()) } returns completed
        every { exitValue() } returns exitCode
        every { inputStream } returns ByteArrayInputStream(stderr.toByteArray())
        every { destroyForcibly() } returns this
    }
}
