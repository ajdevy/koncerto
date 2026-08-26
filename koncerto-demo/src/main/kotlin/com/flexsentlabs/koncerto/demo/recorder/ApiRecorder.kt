package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import com.flexsentlabs.koncerto.demo.recorder.api.ApiRunResult
import com.flexsentlabs.koncerto.demo.recorder.api.ApiScenario
import com.flexsentlabs.koncerto.demo.recorder.api.ApiScenarioParser
import com.flexsentlabs.koncerto.demo.recorder.api.ApiScriptBuilder
import com.flexsentlabs.koncerto.demo.recorder.api.OpenApiInventory
import com.flexsentlabs.koncerto.demo.recorder.api.RecorderEnv
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Records a server-side demo: real curl calls against the deployed API, executed in a visible
 * terminal inside the recorder container and captured as video.
 *
 * This is the platform for tickets with no user-facing UI, where the thing to demonstrate is the
 * HTTP contract itself. It reuses the browser recorder's proven container model — an ephemeral
 * `--rm` container joined to the target's Docker network, Xvfb plus ffmpeg x11grab — and swaps
 * Chromium for an xterm running the scenario's curl commands.
 *
 * Two things distinguish it from a screen capture of a shell. The commands are generated from the
 * ticket's scenario and executed for real against the deployment, and their assertions decide the
 * verdict: a run whose expected status codes don't match fails the recording, so the pipeline's
 * repair/escalation loop sees a broken endpoint instead of publishing a video of one.
 *
 * Container/OS plumbing, so excluded from coverage aggregation like the other recorders; the script
 * generation, scenario parsing and result interpretation it delegates to are all unit-tested.
 */
class ApiRecorder(
    private val specFetcher: OpenApiSpecFetcher? = null
) : DemoRecorder {

    override val platform: DemoPlatform = DemoPlatform.API

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val docker = ProcessBuilder("docker", "info").redirectErrorStream(true).start()
            docker.waitFor(10, TimeUnit.SECONDS) && docker.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(
        config: RecordingConfig,
        outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> = withContext(Dispatchers.IO) {
        val containerName = parseContainerName(config.targetUrl)
            ?: return@withContext failure(
                "targetUrl '${config.targetUrl}' is not an internal container URL " +
                    "(expected http://<containerName>:<port>) — recording runs in Docker and needs " +
                    "the target's internal-network address, not a host-facing one"
            )

        val scenario = loadScenario(config)
            ?: return@withContext failure(
                "no usable API scenario: ${config.scenarioPath.ifBlank { "<none staged>" }} did not " +
                    "parse into any request, and the target published no OpenAPI document to derive " +
                    "a read-only walk from"
            )

        val runDir = File(File(System.getProperty("user.home"), ".koncerto/demo-recorder-tmp"), UUID.randomUUID().toString())
        runDir.mkdirs()
        try {
            val resultFile = File(runDir, RESULT_FILE)
            File(runDir, DEMO_SCRIPT).writeText(
                ApiScriptBuilder.build(scenario, "/work/$RESULT_FILE", config.issueIdentifier)
            )
            File(runDir, RECORD_SCRIPT).writeText(buildRecordScript(config))

            val image = RecorderImage().ensureAvailable().getOrElse { e ->
                return@withContext failure("recorder image unavailable: ${e.message}")
            }
            val network = resolveNetwork(containerName)
                ?: return@withContext failure("could not resolve docker network for $containerName")

            val env = linkedMapOf(
                "TARGET_URL" to config.targetUrl,
                "API_OUTPUT_PATH" to "/work/output.${config.outputFormat}",
                "API_STARTED_FILE" to "/work/started.flag",
                "API_MAX_DURATION_SECONDS" to config.maxDurationSeconds.toString()
            )
            // Demo credentials (staged by the orchestrator) reach the scenario as shell variables, so
            // an authenticated endpoint can be demonstrated. Secret — never traced.
            if (config.credentialsPath.isNotBlank()) {
                val credentials = File(config.credentialsPath)
                if (credentials.isFile) {
                    RecorderEnv.parseKeyValues(credentials.readText()).forEach { (k, v) -> env[k] = v }
                }
            }

            val startTime = System.currentTimeMillis()
            val process = ProcessBuilder(
                listOf("docker", "run", "--rm", "--name", "koncerto-api-recorder-${UUID.randomUUID()}",
                    "--network", network, "-v", "${runDir.absolutePath}:/work") +
                    env.flatMap { (k, v) -> listOf("-e", "$k=$v") } +
                    listOf(image, "bash", "/work/$RECORD_SCRIPT")
            ).redirectErrorStream(true).start()

            val completed = process.waitFor(config.maxDurationSeconds + GRACE_SECONDS, TimeUnit.SECONDS)
            val consoleOutput = process.inputStream.bufferedReader().use { it.readText() }
            if (!completed) {
                process.destroyForcibly()
                return@withContext failure("API recording timed out after ${config.maxDurationSeconds}s")
            }

            val produced = File(runDir, "output.${config.outputFormat}")
            val runResult = ApiRunResult.parse(
                if (resultFile.isFile) resultFile.readText() else "",
                scenario.requestCount
            )
            runResult.failureReason()?.let { reason ->
                // Keep the video: a recording of the failing call is exactly what the repair loop and
                // a human reviewer need to see.
                if (produced.isFile) produced.copyTo(outputFile, overwrite = true)
                return@withContext failure(reason)
            }
            if (!produced.isFile || produced.length() == 0L) {
                return@withContext failure(
                    "API recording produced no video (exit ${process.exitValue()}): " +
                        consoleOutput.takeLast(CONSOLE_TAIL)
                )
            }
            produced.copyTo(outputFile, overwrite = true)

            DemoResult.Success(
                DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = System.currentTimeMillis() - startTime,
                    fileSizeBytes = outputFile.length(),
                    format = config.outputFormat
                )
            )
        } catch (e: Exception) {
            failure("API recording error: ${e.message}")
        } finally {
            runCatching { runDir.deleteRecursively() }
        }
    }

    /**
     * The authored scenario when one parses, otherwise a read-only walk derived from the target's
     * own OpenAPI document. The fallback matters because the browser recorder degrades to this
     * platform: without it, a failed web recording would yield nothing at all.
     */
    private suspend fun loadScenario(config: RecordingConfig): ApiScenario? {
        val authored = config.scenarioPath
            .takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { ApiScenarioParser.parse(it.readText()) }
        if (authored != null) return authored
        val spec = specFetcher?.fetch(config.targetUrl) ?: return null
        return OpenApiInventory.discoveryScenario(spec)
    }

    /**
     * Xvfb hosts an xterm running the demo, and ffmpeg grabs that display — the same capture path
     * the browser recorder uses, so the two produce directly comparable video.
     */
    internal fun buildRecordScript(config: RecordingConfig): String = """
#!/bin/bash
set -u
export DISPLAY=:99
export TERM=xterm-256color

Xvfb :99 -screen 0 ${config.width}x${config.height}x24 -nolisten tcp &
XVFB_PID=${'$'}!
sleep 2

xterm -geometry ${terminalColumns(config)}x${terminalRows(config)} \
  -fa 'Monospace' -fs 13 -bg black -fg '#e6e6e6' \
  -e bash /work/$DEMO_SCRIPT &
XTERM_PID=${'$'}!

ffmpeg -y -f x11grab -video_size ${config.width}x${config.height} \
  -framerate ${config.frameRate} -i :99 \
  -c:v libvpx-vp9 -b:v 400k -t ${'$'}{API_MAX_DURATION_SECONDS} \
  "${'$'}{API_OUTPUT_PATH}" &
FFMPEG_PID=${'$'}!

sleep 2
touch "${'$'}{API_STARTED_FILE}"

# The demo drives the length of the video: capture stops once the scenario finishes, rather than
# padding every recording out to the maximum duration.
wait ${'$'}{XTERM_PID} 2>/dev/null || true
sleep 2
kill -INT ${'$'}{FFMPEG_PID} 2>/dev/null || true
wait ${'$'}{FFMPEG_PID} 2>/dev/null || true
kill ${'$'}{XVFB_PID} 2>/dev/null || true
exit 0
""".trim()

    /** Character cell counts that fill the frame at the xterm font size used above. */
    internal fun terminalColumns(config: RecordingConfig): Int = (config.width / 8).coerceIn(80, 240)

    internal fun terminalRows(config: RecordingConfig): Int = (config.height / 16).coerceIn(24, 80)

    private fun parseContainerName(targetUrl: String): String? = try {
        java.net.URI(targetUrl).host?.takeIf { it.isNotBlank() && it != "localhost" }
    } catch (_: Exception) {
        null
    }

    private fun resolveNetwork(containerName: String): String? = try {
        val p = ProcessBuilder("docker", "inspect", containerName, "--format", "{{.HostConfig.NetworkMode}}")
            .redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0) output.takeIf { it.isNotBlank() } else null
    } catch (_: Exception) {
        null
    }

    private fun failure(message: String): DemoResult<DemoRecorder.RecordingResult> =
        DemoResult.Failure(DemoError.RecordingFailed(RuntimeException(message)))

    private companion object {
        const val DEMO_SCRIPT = "demo.sh"
        const val RECORD_SCRIPT = "record.sh"
        const val RESULT_FILE = "api-result.jsonl"
        const val GRACE_SECONDS = 120L
        const val CONSOLE_TAIL = 2000
    }
}
