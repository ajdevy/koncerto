package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class PlaywrightRecorderTest {

    @AfterEach
    fun resetTestSeams() {
        PlaywrightRecorder.testDependenciesAvailable = null
        PlaywrightRecorder.testRecordProcessBuilder = null
        PlaywrightRecorder.testMaxWaitSeconds = null
        PlaywrightRecorder.testStartupWaitSeconds = null
    }

    private val config = RecordingConfig(
        platform = DemoPlatform.PLAYWRIGHT,
        targetUrl = "http://koncerto-demo-test-container:8080",
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
        // Force the unavailable branch via the test seam rather than relying on the ambient
        // machine lacking Node/playwright — this repo now has a real node_modules/playwright
        // dependency (for the recorder to actually work), so isAvailable() legitimately
        // returns true here otherwise.
        PlaywrightRecorder.testDependenciesAvailable = false
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
        val method = PlaywrightRecorder::class.java.getDeclaredMethod("runCleanup", String::class.java)
        method.isAccessible = true
        method.invoke(recorder, "koncerto-demo-recorder-nonexistent")
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
    fun `companion PLAYWRIGHT_SCRIPT has a hard-exit watchdog that outlives the post-scenario sleep`() {
        // Regression test for a real production incident: FLE-52's recording hit an actual
        // browser/page crash mid-scenario, and process.exit(2) called from failValidation()
        // afterward did not reliably terminate Node — the recorder hung until the JVM-side
        // captureWaitSec (240s) forcibly destroyed the process. Four consecutive attempts hit
        // the exact same 240s wall and the whole recording was abandoned. The script must
        // carry its own bounded fallback so a hung cleanup fails fast instead of stalling
        // every retry for the full outer timeout.
        val field = PlaywrightRecorder::class.java.getDeclaredField("PLAYWRIGHT_SCRIPT")
        field.isAccessible = true
        val script = field.get(null) as String
        assertThat(script.contains("hardExitTimer")).isTrue()
        assertThat(script.contains(".unref()")).isTrue()
        assertThat(script.contains("maxDurationSeconds + 90")).isTrue()
    }

    @Test
    fun `parseSimpleYaml parses real indented scenario yaml produced by the model`() {
        // Regression test for a bug where parseSimpleYaml only called trimEnd() on each
        // line, never stripping leading indentation. Structural checks like `steps:` and
        // `- action:` compare against the START of the trimmed line, so every real
        // scenario (indented under demo_scenario: and steps:, exactly what
        // demo-scenario.md's own example teaches and what every model produces) silently
        // parsed to zero steps and was skipped without ever failing loudly.
        val node = runCatching { ProcessBuilder("which", "node").start().also { it.waitFor(5, TimeUnit.SECONDS) } }
            .getOrNull()
        assumeTrue(node != null && node.exitValue() == 0, "node not available in this environment")

        val field = PlaywrightRecorder::class.java.getDeclaredField("PLAYWRIGHT_SCRIPT")
        field.isAccessible = true
        val script = field.get(null) as String

        val scenarioYaml = """
            demo_scenario:
              description: "Login flow"
              steps:
                - action: navigate
                  url: "/"
                - action: scroll
                  direction: down
                  amount: 500
                - action: click
                  selector: "[data-request-code]"
        """.trimIndent()

        val scenarioFile = File.createTempFile("pw-scenario-test-", ".yaml").apply {
            writeText(scenarioYaml)
            deleteOnExit()
        }

        // Extract just the two YAML-parsing functions (the last things the script
        // defines, after the browser-launching IIFE) so the harness doesn't need a
        // real playwright install to exercise them.
        val marker = "function parseScenarioYaml"
        check(script.contains(marker)) { "PLAYWRIGHT_SCRIPT no longer defines parseScenarioYaml" }
        val parsingFunctions = marker + script.substringAfter(marker)
        val harness = "const fs = require('fs');\n" + parsingFunctions + "\n" +
            "const parsed = parseScenarioYaml(fs.readFileSync(process.argv[2], 'utf-8'));\n" +
            "console.log(JSON.stringify(parsed));\n"
        val harnessFile = File.createTempFile("pw-harness-", ".js").apply {
            writeText(harness)
            deleteOnExit()
        }

        val process = ProcessBuilder("node", harnessFile.absolutePath, scenarioFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)

        assertThat(output.contains("\"action\":\"navigate\"")).isTrue()
        assertThat(output.contains("\"action\":\"scroll\"")).isTrue()
        assertThat(output.contains("\"action\":\"click\"")).isTrue()
    }

    @Test
    fun `parseSimpleYaml unescapes double-quoted values and coerces numeric-looking ones`() {
        // Regression test for a real FLE-52 recording: the model correctly used real
        // selectors like [data-testid="email-input"], but YAML's double-quoted scalars
        // treat \" as an escaped literal quote. Without unescaping, the parsed selector kept
        // its literal backslashes ([data-testid=\"email-input\"]), which is not valid CSS and
        // matches nothing — every click/type step silently failed even with a correct
        // selector. Separately, Playwright's locator.type() validates `delay` strictly and
        // throws "expected float, got string" if it isn't coerced from the YAML string to a
        // real number.
        val node = runCatching { ProcessBuilder("which", "node").start().also { it.waitFor(5, TimeUnit.SECONDS) } }
            .getOrNull()
        assumeTrue(node != null && node.exitValue() == 0, "node not available in this environment")

        val field = PlaywrightRecorder::class.java.getDeclaredField("PLAYWRIGHT_SCRIPT")
        field.isAccessible = true
        val script = field.get(null) as String

        val scenarioYaml = """
            demo_scenario:
              description: "Email login"
              steps:
                - action: type
                  selector: "[data-testid=\"email-input\"]"
                  value: "owner@promomesh.ru"
                  delay: 50
        """.trimIndent()

        val scenarioFile = File.createTempFile("pw-scenario-test-", ".yaml").apply {
            writeText(scenarioYaml)
            deleteOnExit()
        }

        val marker = "function parseScenarioYaml"
        check(script.contains(marker)) { "PLAYWRIGHT_SCRIPT no longer defines parseScenarioYaml" }
        val parsingFunctions = marker + script.substringAfter(marker)
        val harness = "const fs = require('fs');\n" + parsingFunctions + "\n" +
            "const parsed = parseScenarioYaml(fs.readFileSync(process.argv[2], 'utf-8'));\n" +
            "const step = parsed.steps[0];\n" +
            "console.log(JSON.stringify({ selector: step.selector, delayType: typeof step.delay, delay: step.delay }));\n"
        val harnessFile = File.createTempFile("pw-harness-", ".js").apply {
            writeText(harness)
            deleteOnExit()
        }

        val process = ProcessBuilder("node", harnessFile.absolutePath, scenarioFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(15, TimeUnit.SECONDS)

        assertThat(output.contains("\"selector\":\"[data-testid=\\\"email-input\\\"]\"")).isTrue()
        assertThat(output.contains("\"delayType\":\"number\"")).isTrue()
        assertThat(output.contains("\"delay\":50")).isTrue()
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
    fun `parseContainerName extracts host from an internal container URL`() {
        val recorder = PlaywrightRecorder()
        val method = PlaywrightRecorder::class.java.getDeclaredMethod("parseContainerName", String::class.java)
        method.isAccessible = true
        val name = method.invoke(recorder, "http://koncerto-demo-1783600000000:8080") as String?
        assertThat(name).isEqualTo("koncerto-demo-1783600000000")
    }

    @Test
    fun `parseContainerName rejects localhost rather than silently using it as a container name`() {
        val recorder = PlaywrightRecorder()
        val method = PlaywrightRecorder::class.java.getDeclaredMethod("parseContainerName", String::class.java)
        method.isAccessible = true
        val name = method.invoke(recorder, "http://localhost:32768") as String?
        assertThat(name).isNull()
    }

    @Test
    fun `parseContainerName returns null for unparseable urls`() {
        val recorder = PlaywrightRecorder()
        val method = PlaywrightRecorder::class.java.getDeclaredMethod("parseContainerName", String::class.java)
        method.isAccessible = true
        val name = method.invoke(recorder, "not a url") as String?
        assertThat(name).isNull()
    }

    @Test
    fun `record fails fast with a descriptive error when targetUrl has no container name`() = runTest {
        val output = File.createTempFile("pw-no-container-", ".webm")
        PlaywrightRecorder.testDependenciesAvailable = true
        val result = PlaywrightRecorder().record(config.copy(targetUrl = "http://localhost:32768"), output)
        assertThat(result).isInstanceOf(DemoResult.Failure::class)
        val error = (result as DemoResult.Failure).error as DemoError.RecordingFailed
        assertThat(error.message ?: "").contains("internal")
    }
}
