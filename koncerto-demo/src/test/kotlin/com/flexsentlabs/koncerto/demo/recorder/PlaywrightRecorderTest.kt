package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class PlaywrightRecorderTest {

    private val config = RecordingConfig(
        platform = DemoPlatform.PLAYWRIGHT,
        targetUrl = "http://localhost:3000",
        maxDurationSeconds = 5
    )

    @Test
    fun `platform is PLAYWRIGHT`() {
        assert(PlaywrightRecorder().platform == DemoPlatform.PLAYWRIGHT)
    }

    @Test
    fun `isAvailable returns false without throwing`() = runTest {
        PlaywrightRecorder().isAvailable()
    }

    @Test
    fun `record returns RecorderNotAvailable when dependencies missing`() = runTest {
        val output = File.createTempFile("pw-test-", ".webm")
        val result = PlaywrightRecorder().record(config, output)
        assert(result is DemoResult.Failure)
        val error = (result as DemoResult.Failure).error
        assert(error is DemoError.RecorderNotAvailable)
    }
}
