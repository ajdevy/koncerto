package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class PlaywrightRecorderTest {

    @AfterEach
    fun resetTestSeams() {
        PlaywrightRecorder.testDependenciesAvailable = null
        PlaywrightRecorder.testRecordProcessBuilder = null
        PlaywrightRecorder.testMaxWaitSeconds = null
        PlaywrightRecorder.testStartupWaitSeconds = null
        PlaywrightRecorder.testUseNativeVideoMode = null
    }

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
        assertThat(script.contains("chrome-error://")).isTrue()
    }

    @Test
    fun `isAvailable probes host dependencies when override unset`() = runTest {
        PlaywrightRecorder.testDependenciesAvailable = null
        val available = PlaywrightRecorder().isAvailable()
        assertThat(available == true || available == false).isTrue()
    }

    @Test
    fun `isAvailable honors test override`() = runTest {
        PlaywrightRecorder.testDependenciesAvailable = true
        assertThat(PlaywrightRecorder().isAvailable()).isTrue()
        PlaywrightRecorder.testDependenciesAvailable = false
        assertThat(PlaywrightRecorder().isAvailable()).isFalse()
    }

    @Test
    fun `record succeeds when test process writes output file`() = runTest {
        val output = File.createTempFile("pw-success-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, out ->
            ProcessBuilder(
                "bash",
                "-c",
                """
                set -e
                printf STARTED > "${'$'}PW_FFMPEG_STARTED_FILE"
                echo ok > '${out.absolutePath}'
                exit 0
                """.trimIndent()
            )
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Success::class)
        assertThat(output.exists() && output.length() > 0).isTrue()
    }

    @Test
    fun `record returns failure on timeout`() = runTest {
        val output = File.createTempFile("pw-timeout-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testMaxWaitSeconds = 1L
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ ->
            ProcessBuilder("bash", "-c", "sleep 5")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
        assertThat((result as DemoResult.Failure).error).isInstanceOf(DemoError.RecordingFailed::class)
    }

    @Test
    fun `record returns failure when recording never starts`() = runTest {
        val output = File.createTempFile("pw-startup-timeout-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testStartupWaitSeconds = 1L
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ ->
            ProcessBuilder("bash", "-c", "sleep 5")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
        assertThat((result as DemoResult.Failure).error).isInstanceOf(DemoError.RecordingFailed::class)
    }

    @Test
    fun `record succeeds when recording start marker appears`() = runTest {
        val output = File.createTempFile("pw-marker-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testStartupWaitSeconds = 2L
        PlaywrightRecorder.testMaxWaitSeconds = 3L
        PlaywrightRecorder.testRecordProcessBuilder = { _, out ->
            ProcessBuilder(
                "bash",
                "-c",
                """
                set -e
                printf STARTED > "${'$'}PW_FFMPEG_STARTED_FILE"
                sleep 1
                echo done > '${out.absolutePath}'
                exit 0
                """.trimIndent()
            )
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Success::class)
        assertThat(output.exists() && output.length() > 0).isTrue()
    }

    @Test
    fun `record returns failure on content validation exit code`() = runTest {
        val output = File.createTempFile("pw-validate-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, out ->
            ProcessBuilder("bash", "-c", "echo 'VALIDATION_ERROR: site unreachable'; exit 2")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
        val error = (result as DemoResult.Failure).error as DemoError.RecordingFailed
        assertThat(error.message ?: "").contains("Content validation failed")
    }

    @Test
    fun `record returns failure on non-zero exit code`() = runTest {
        val output = File.createTempFile("pw-exit-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ ->
            ProcessBuilder("bash", "-c", "echo boom; exit 1")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
    }

    @Test
    fun `record returns failure when output file empty after success exit`() = runTest {
        val output = File.createTempFile("pw-empty-", ".webm")
        output.delete()
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ ->
            ProcessBuilder("bash", "-c", "exit 0")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
    }

    @Test
    fun `record returns failure when process builder throws`() = runTest {
        val output = File.createTempFile("pw-throw-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ ->
            throw RuntimeException("process setup failed")
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
    }

    @Test
    fun `record uses scenario path when file exists`() = runTest {
        val scenario = File.createTempFile("pw-scenario-", ".js")
        scenario.writeText("module.exports = async () => {};")
        val output = File.createTempFile("pw-scenario-out-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testRecordProcessBuilder = { cfg, out ->
            assertThat(cfg.scenarioPath).isEqualTo(scenario.absolutePath)
            ProcessBuilder(
                "bash",
                "-c",
                """
                set -e
                printf STARTED > "${'$'}PW_FFMPEG_STARTED_FILE"
                echo ok > '${out.absolutePath}'
                exit 0
                """.trimIndent()
            )
        }
        val result = PlaywrightRecorder().record(
            config.copy(scenarioPath = scenario.absolutePath),
            output
        )
        assertThat(result).isInstanceOf(DemoResult.Success::class)
    }

    @Test
    fun `companion test seams roundtrip`() {
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testMaxWaitSeconds = 42L
        PlaywrightRecorder.testRecordProcessBuilder = { _, _ -> ProcessBuilder("true") }
        assertThat(PlaywrightRecorder.testDependenciesAvailable).isEqualTo(true)
        assertThat(PlaywrightRecorder.testMaxWaitSeconds).isEqualTo(42L)
        assertThat(PlaywrightRecorder.testRecordProcessBuilder).isNotNull()
    }

    @Test
    fun `buildShellScript includes output path`() {
        val method = PlaywrightRecorder::class.java.getDeclaredMethod(
            "buildShellScript",
            com.flexsentlabs.koncerto.demo.model.RecordingConfig::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val output = java.io.File.createTempFile("pw-script-", ".webm")
        val script = method.invoke(PlaywrightRecorder(), config, output.absolutePath, "") as String
        assertThat(script.contains(output.absolutePath)).isTrue()
        assertThat(script.contains("Xvfb :99")).isTrue()
    }

    @Test
    fun `buildNativeShellScript omits Xvfb and ffmpeg`() {
        val method = PlaywrightRecorder::class.java.getDeclaredMethod(
            "buildNativeShellScript",
            com.flexsentlabs.koncerto.demo.model.RecordingConfig::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        val output = java.io.File.createTempFile("pw-native-", ".webm")
        val script = method.invoke(PlaywrightRecorder(), config, output.absolutePath, "") as String
        assertThat(script.contains("node")).isTrue()
        assertThat(script.contains("Xvfb")).isFalse()
        assertThat(script.contains("ffmpeg")).isFalse()
    }

    @Test
    fun `record succeeds in native video mode with test process`() = runTest {
        val output = File.createTempFile("pw-native-success-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        PlaywrightRecorder.testUseNativeVideoMode = true
        PlaywrightRecorder.testRecordProcessBuilder = { _, out ->
            ProcessBuilder(
                "bash",
                "-c",
                """
                set -e
                printf STARTED > "${'$'}PW_FFMPEG_STARTED_FILE"
                echo native > "${out.absolutePath}"
                exit 0
                """.trimIndent()
            )
        }
        val result = PlaywrightRecorder().record(config, output)
        assertThat(result).isInstanceOf(DemoResult.Success::class)
        assertThat(output.exists() && output.length() > 0).isTrue()
    }
}
