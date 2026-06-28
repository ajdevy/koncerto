package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
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

    @Test
    fun `buildShellScript includes target url and output path`() {
        val recorder = PlaywrightRecorder()
        val method = PlaywrightRecorder::class.java.getDeclaredMethod(
            "buildShellScript",
            RecordingConfig::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val script = method.invoke(recorder, config, "/tmp/out.webm", "") as String
        assertThat(script.contains("TARGET_URL")).isTrue()
        assertThat(script.contains("/tmp/out.webm")).isTrue()
        assertThat(script.contains("ffmpeg")).isTrue()
    }

    @Test
    fun `runCleanup does not throw`() {
        val recorder = PlaywrightRecorder()
        val method = PlaywrightRecorder::class.java.getDeclaredMethod("runCleanup")
        method.isAccessible = true
        method.invoke(recorder)
    }

    @Test
    fun `companion PLAYWRIGHT_SCRIPT contains chromium launch`() {
        val field = PlaywrightRecorder::class.java.getDeclaredField("PLAYWRIGHT_SCRIPT")
        field.isAccessible = true
        val script = field.get(null) as String
        assertThat(script.contains("chromium.launch")).isTrue()
        assertThat(script.contains("networkidle")).isTrue()
    }
}
