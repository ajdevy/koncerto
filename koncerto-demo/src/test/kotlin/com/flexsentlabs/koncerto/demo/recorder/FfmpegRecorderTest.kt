package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import org.junit.jupiter.api.Test

class FfmpegRecorderTest {

    private val config = RecordingConfig(
        platform = DemoPlatform.FFMPEG,
        captureInputIndex = ":0",
        frameRate = 15,
        timestampOverlay = true
    )

    @Test
    fun `ffmpegDetectInputArgs uses avfoundation on macOS`() {
        val args = ffmpegDetectInputArgs(config, "mac os x 14.0")

        assert(args == listOf("-f", "avfoundation", "-i", ":0"))
    }

    @Test
    fun `ffmpegDetectInputArgs uses x11grab on Linux`() {
        val args = ffmpegDetectInputArgs(config, "linux")

        assert(args == listOf("-f", "x11grab", "-i", ":0"))
    }

    @Test
    fun `ffmpegDetectInputArgs uses gdigrab on Windows`() {
        val args = ffmpegDetectInputArgs(config, "windows 11")

        assert(args == listOf("-f", "gdigrab", "-i", ":0"))
    }

    @Test
    fun `ffmpegDetectInputArgs falls back to avfoundation default index on unknown OS`() {
        val args = ffmpegDetectInputArgs(config, "freebsd")

        assert(args == listOf("-f", "avfoundation", "-i", "1"))
    }

    @Test
    fun `ffmpegBuildFilterComplex includes fps and timestamp overlay`() {
        val filter = ffmpegBuildFilterComplex(config)

        assert(filter.startsWith("fps=15"))
        assert(filter.contains("drawtext"))
        assert(filter.contains("localtime"))
    }

    @Test
    fun `ffmpegBuildFilterComplex omits overlay when disabled`() {
        val filter = ffmpegBuildFilterComplex(config.copy(timestampOverlay = false))

        assert(filter == "fps=15")
        assert(!filter.contains("drawtext"))
    }
}
