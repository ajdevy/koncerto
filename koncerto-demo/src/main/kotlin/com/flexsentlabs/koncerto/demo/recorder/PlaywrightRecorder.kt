package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import com.flexsentlabs.koncerto.logging.RollingTraceFiles
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaywrightRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        testDependenciesAvailable?.let { return@withContext it }
        try {
            val node = ProcessBuilder("which", "node").start()
            if (!(node.waitFor(5, TimeUnit.SECONDS) && node.exitValue() == 0)) return@withContext false
            val playwright = ProcessBuilder("node", "-e", "require('playwright')").start()
            if (!(playwright.waitFor(15, TimeUnit.SECONDS) && playwright.exitValue() == 0)) return@withContext false
            if (resolveChromiumExecutablePath().isNullOrBlank()) return@withContext false
            if (useNativeVideoMode()) return@withContext true
            val xvfb = ProcessBuilder("which", "Xvfb").start()
            if (!(xvfb.waitFor(5, TimeUnit.SECONDS) && xvfb.exitValue() == 0)) return@withContext false
            val ffmpeg = ProcessBuilder("which", "ffmpeg").start()
            ffmpeg.waitFor(5, TimeUnit.SECONDS) && ffmpeg.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun record(config: RecordingConfig, outputFile: File): DemoResult<DemoRecorder.RecordingResult> =
        withContext(Dispatchers.IO) {
            var pwScript: File? = null
            var shellScript: File? = null
            var startedMarker: File? = null
            val traceDir = File("/tmp/koncerto-demo").toPath()
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("playwright"))
                }

                val startTime = System.currentTimeMillis()
                traceRecordingStep(traceDir, "recording_attempt", "start", mapOf(
                    "target_url" to config.targetUrl,
                    "scenario_path" to config.scenarioPath,
                    "output_path" to outputFile.absolutePath,
                    "chromium_path" to (resolveChromiumExecutablePath() ?: ""),
                ))

                pwScript = File.createTempFile("pw-recorder-", ".js")
                val scenarioArg = if (config.scenarioPath.isNotBlank() && File(config.scenarioPath).exists()) config.scenarioPath else ""
                pwScript.writeText(PLAYWRIGHT_SCRIPT)
                pwScript.deleteOnExit()

                shellScript = File.createTempFile("pw-record-", ".sh")
                startedMarker = File.createTempFile("pw-recording-started-", ".flag").apply {
                    delete()
                    deleteOnExit()
                }
                shellScript.writeText(
                    if (useNativeVideoMode()) {
                        buildNativeShellScript(config, outputFile.absolutePath, scenarioArg)
                    } else {
                        buildShellScript(config, outputFile.absolutePath, scenarioArg)
                    }
                )
                shellScript.setExecutable(true)
                shellScript.deleteOnExit()

                val chromiumPath = resolveChromiumExecutablePath()
                val startedMarkerFile = requireNotNull(startedMarker) { "Recording started marker was not created" }
                val pb = testRecordProcessBuilder?.invoke(config, outputFile)
                    ?: ProcessBuilder("bash", shellScript.absolutePath)
                    .redirectErrorStream(true)
                val env = pb.environment()
                // node <file> resolves require() from the SCRIPT's own directory, not the
                // process's cwd (unlike `node -e`, which isAvailable() uses above and which
                // does resolve from cwd). Since pwScript lives in the OS temp dir, it has no
                // node_modules in its ancestry — point NODE_PATH at ours so it's found anyway.
                val nodeModulesPath = File(System.getProperty("user.dir"), "node_modules").absolutePath
                val existingNodePath = System.getenv("NODE_PATH")
                env["NODE_PATH"] = if (existingNodePath.isNullOrBlank()) {
                    nodeModulesPath
                } else {
                    "$existingNodePath:$nodeModulesPath"
                }
                env["TARGET_URL"] = config.targetUrl
                env["SCENARIO_PATH"] = scenarioArg
                env["PW_SCRIPT_PATH"] = pwScript.absolutePath
                env["PW_FFMPEG_STARTED_FILE"] = startedMarkerFile.absolutePath
                env["PW_OUTPUT_PATH"] = outputFile.absolutePath
                env["PW_MAX_DURATION_SECONDS"] = config.maxDurationSeconds.toString()
                env["PW_USE_NATIVE_VIDEO"] = useNativeVideoMode().toString()
                if (!chromiumPath.isNullOrBlank()) {
                    env["PW_CHROMIUM_PATH"] = chromiumPath
                }
                val process = pb.start()

                val startupWaitSec = testStartupWaitSeconds ?: 90L
                val captureWaitSec = testMaxWaitSeconds ?: (config.maxDurationSeconds + 120L)
                if (!waitForFile(startedMarkerFile, startupWaitSec, process)) {
                    val exitedEarly = !process.isAlive
                    val earlyExitCode = if (exitedEarly) runCatching { process.exitValue() }.getOrNull() else null
                    val earlyOutput = if (exitedEarly) {
                        process.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        process.destroyForcibly()
                        ""
                    }
                    runCleanup()
                    if (exitedEarly && earlyExitCode == 2) {
                        traceRecordingStep(traceDir, "recording_attempt", "content_validation_failed", mapOf(
                            "target_url" to config.targetUrl,
                            "scenario_path" to scenarioArg,
                            "output_path" to outputFile.absolutePath,
                            "output_excerpt" to earlyOutput.take(300)
                        ))
                        return@withContext DemoResult.Failure(
                            DemoError.RecordingFailed(RuntimeException("Content validation failed: $earlyOutput"))
                        )
                    }
                    if (exitedEarly && earlyExitCode != null) {
                        traceRecordingStep(traceDir, "recording_attempt", "failed", mapOf(
                            "target_url" to config.targetUrl,
                            "scenario_path" to scenarioArg,
                            "output_path" to outputFile.absolutePath,
                            "exit_code" to earlyExitCode.toString(),
                            "output_excerpt" to earlyOutput.take(300)
                        ))
                        return@withContext DemoResult.Failure(
                            DemoError.RecordingFailed(RuntimeException("Recording exited with code $earlyExitCode: $earlyOutput"))
                        )
                    }
                    traceRecordingStep(traceDir, "recording_attempt", "startup_timeout", mapOf(
                        "target_url" to config.targetUrl,
                        "scenario_path" to scenarioArg,
                        "output_path" to outputFile.absolutePath
                    ))
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Recording startup timed out"))
                    )
                }

                traceRecordingStep(traceDir, "recording_attempt", "ffmpeg_started", mapOf(
                    "target_url" to config.targetUrl,
                    "scenario_path" to scenarioArg,
                    "output_path" to outputFile.absolutePath
                ))

                val completed = process.waitFor(captureWaitSec, TimeUnit.SECONDS)
                val durationMs = System.currentTimeMillis() - startTime

                if (!completed) {
                    process.destroyForcibly()
                    runCleanup()
                    traceRecordingStep(traceDir, "recording_attempt", "timeout", mapOf(
                        "target_url" to config.targetUrl,
                        "scenario_path" to scenarioArg,
                        "output_path" to outputFile.absolutePath,
                        "wait_sec" to captureWaitSec.toString()
                    ))
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Recording timed out"))
                    )
                }

                val exitCode = process.exitValue()
                val output = process.inputStream.bufferedReader().use { it.readText() }

                if (exitCode == 2) {
                    runCleanup()
                    traceRecordingStep(traceDir, "recording_attempt", "content_validation_failed", mapOf(
                        "target_url" to config.targetUrl,
                        "scenario_path" to scenarioArg,
                        "output_path" to outputFile.absolutePath
                    ))
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Content validation failed"))
                    )
                }

                if (exitCode != 0) {
                    runCleanup()
                    traceRecordingStep(traceDir, "recording_attempt", "failed", mapOf(
                        "target_url" to config.targetUrl,
                        "scenario_path" to scenarioArg,
                        "output_path" to outputFile.absolutePath,
                        "exit_code" to exitCode.toString(),
                        "output_excerpt" to output.take(300)
                    ))
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Recording exited with code $exitCode: $output"))
                    )
                }

                if (!outputFile.exists() || outputFile.length() == 0L) {
                    runCleanup()
                    traceRecordingStep(traceDir, "recording_attempt", "empty_output", mapOf(
                        "target_url" to config.targetUrl,
                        "scenario_path" to scenarioArg,
                        "output_path" to outputFile.absolutePath
                    ))
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Output file is empty or missing"))
                    )
                }

                traceRecordingStep(traceDir, "recording_attempt", "completed", mapOf(
                    "target_url" to config.targetUrl,
                    "scenario_path" to scenarioArg,
                    "output_path" to outputFile.absolutePath,
                    "duration_ms" to durationMs.toString(),
                    "file_size" to outputFile.length().toString()
                ))
                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = "webm"
                ))
            } catch (e: Exception) {
                runCleanup()
                traceRecordingStep(traceDir, "recording_attempt", "error", mapOf(
                    "target_url" to config.targetUrl,
                    "scenario_path" to config.scenarioPath,
                    "output_path" to outputFile.absolutePath,
                    "error" to (e.message ?: "unknown")
                ))
                DemoResult.Failure(DemoError.RecordingFailed(e))
            } finally {
                pwScript?.delete()
                shellScript?.delete()
                startedMarker?.delete()
            }
        }

    private fun buildShellScript(config: RecordingConfig, outputPath: String, scenarioPath: String = ""): String = """#!/bin/bash
set -e
export DISPLAY=:99

READY_FILE=$(mktemp /tmp/pw-ready-XXXXXX)
FFMPEG_STARTED_FILE="${'$'}{PW_FFMPEG_STARTED_FILE}"
NODE_PID=""
XVFB_PID=""

cleanup() {
rm -f "${'$'}{READY_FILE}" 2>/dev/null || true
  if [ -n "${'$'}{NODE_PID}" ]; then
    kill ${'$'}{NODE_PID} 2>/dev/null || true
    wait ${'$'}{NODE_PID} 2>/dev/null || true
  fi
  if [ -n "${'$'}{XVFB_PID}" ]; then
    kill ${'$'}{XVFB_PID} 2>/dev/null || true
    wait ${'$'}{XVFB_PID} 2>/dev/null || true
  fi
}
trap cleanup EXIT

Xvfb :99 -screen 0 ${config.width}x${config.height}x24 -ac 2>/dev/null &
XVFB_PID=${'$'}!
sleep 1

SCENARIO_ARGS=""
if [ -n "${'$'}{SCENARIO_PATH}" ] && [ -f "${'$'}{SCENARIO_PATH}" ]; then
  SCENARIO_ARGS="${'$'}{SCENARIO_PATH}"
fi

node "${'$'}{PW_SCRIPT_PATH}" "${'$'}{TARGET_URL}" "${'$'}{READY_FILE}" ${'$'}{SCENARIO_ARGS} &
NODE_PID=${'$'}!

for i in $(seq 1 30); do
  if [ -f "${'$'}{READY_FILE}" ] && grep -q READY "${'$'}{READY_FILE}" 2>/dev/null; then
    break
  fi
  if ! kill -0 ${'$'}{NODE_PID} 2>/dev/null; then
    wait ${'$'}{NODE_PID}
    exit ${'$'}?
  fi
  sleep 1
done

rm -f "${'$'}{READY_FILE}"
if [ -n "${'$'}{FFMPEG_STARTED_FILE}" ]; then
  printf "STARTED" > "${'$'}{FFMPEG_STARTED_FILE}" 2>/dev/null || true
fi

ffmpeg -y -f x11grab -draw_mouse 0 -r ${config.frameRate} -s ${config.width}x${config.height} -i :99.0 \
  -c:v libvpx-vp9 -b:v 200k -t ${config.maxDurationSeconds} "${outputPath}" 2>/dev/null

exit ${'$'}?
"""

    private fun buildNativeShellScript(config: RecordingConfig, outputPath: String, scenarioPath: String = ""): String = """#!/bin/bash
set -e

SCENARIO_ARGS=""
if [ -n "${'$'}{SCENARIO_PATH}" ] && [ -f "${'$'}{SCENARIO_PATH}" ]; then
  SCENARIO_ARGS="${'$'}{SCENARIO_PATH}"
fi

node "${'$'}{PW_SCRIPT_PATH}" "${'$'}{TARGET_URL}" "${'$'}{PW_FFMPEG_STARTED_FILE}" ${'$'}{SCENARIO_ARGS}

test -s "${outputPath}"
"""

    private fun runCleanup() {
        try {
            val rt = Runtime.getRuntime()
            rt.exec(arrayOf("pkill", "-f", "pw-recorder"))
            rt.exec(arrayOf("pkill", "-f", "chrome.*--no-sandbox"))
            rt.exec(arrayOf("pkill", "-f", "Xvfb :99"))
        } catch (_: Exception) {}
    }

    private fun waitForFile(file: File?, timeoutSec: Long, process: Process): Boolean {
        if (file == null) return false
        val deadlineMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSec)
        while (System.currentTimeMillis() < deadlineMs) {
            if (file.exists()) return true
            if (!process.isAlive) return false
            Thread.sleep(250)
        }
        return file.exists()
    }

    private fun resolveChromiumExecutablePath(): String? {
        val envOverride = System.getenv("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH")?.trim()
        if (!envOverride.isNullOrBlank()) return envOverride
        val candidates = listOf(
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "chromium-browser",
            "chromium",
            "/usr/bin/chromium-browser",
            "/usr/lib/chromium/chromium"
        )
        for (candidate in candidates) {
            if (candidate.startsWith("/")) {
                if (File(candidate).canExecute()) return candidate
                continue
            }
            val proc = ProcessBuilder("sh", "-lc", "command -v ${candidate}").start()
            if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                val output = proc.inputStream.bufferedReader().readText().trim()
                if (output.isNotBlank()) return output
            }
        }
        return null
    }

    private fun useNativeVideoMode(): Boolean {
        testUseNativeVideoMode?.let { return it }
        return currentOsName().contains("mac", ignoreCase = true)
    }

    private fun currentOsName(): String = System.getProperty("os.name").orEmpty()

    companion object {
        /** Test seam: when set, bypasses dependency probing in [isAvailable]. */
        @JvmStatic
        var testDependenciesAvailable: Boolean? = null

        /** Test seam: when set, replaces the bash recording process. */
        @JvmStatic
        var testRecordProcessBuilder: ((RecordingConfig, File) -> ProcessBuilder)? = null

        /** Test seam: overrides process wait timeout seconds in [record]. */
        @JvmStatic
        var testMaxWaitSeconds: Long? = null

        /** Test seam: overrides startup wait timeout seconds in [record]. */
        @JvmStatic
        var testStartupWaitSeconds: Long? = null

        /** Test seam: forces native video mode selection. */
        @JvmStatic
        var testUseNativeVideoMode: Boolean? = null

        private val PLAYWRIGHT_SCRIPT = """#!/usr/bin/env node
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const url = process.argv[2];
const readyFile = process.argv[3];
const scenarioFile = process.argv[4];
const outputPath = process.env.PW_OUTPUT_PATH;
const useNativeVideo = process.env.PW_USE_NATIVE_VIDEO === 'true';
const maxDurationSeconds = parseInt(process.env.PW_MAX_DURATION_SECONDS || '120', 10);

if (!url || !readyFile) {
  console.error('Usage: pw-recorder.js <url> <ready-file> [scenario-file]');
  process.exit(1);
}

// Safety net: if the underlying browser/renderer crashes mid-scenario (observed with
// longer, multi-page scenarios), Playwright's own cleanup (context.close/browser.close,
// or even the try/catch around a failed step) can hang indefinitely waiting on a CDP
// connection that will never respond — process.exit() calls made in that state don't
// reliably terminate Node either. Without this, the recorder just hangs until the
// caller's own multi-minute wait times out, and every retry hits the exact same wall.
// unref() means this timer never itself keeps the process alive if everything else
// finishes cleanly and quickly.
const hardExitTimer = setTimeout(() => {
  console.error('[watchdog] forcing exit — browser/cleanup hung past the hard deadline');
  process.exit(1);
}, (maxDurationSeconds + 90) * 1000);
hardExitTimer.unref();

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

function failValidation(message) {
  console.error('VALIDATION_ERROR: ' + message);
  process.exit(2);
}

function extractBodyText(page) {
  return page.evaluate(() => (document.body && document.body.innerText) ? document.body.innerText : '');
}

async function validatePageState(page, response, phase) {
  const currentUrl = page.url() || '';
  const title = (await page.title().catch(() => '')) || '';
  const bodyText = ((await extractBodyText(page).catch(() => '')) || '').trim();
  const combined = (title + '\n' + bodyText).toLowerCase();
  const markers = [
    "this site can't be reached",
    "this site can’t be reached",
    'err_',
    'refused to connect',
    'dns_probe_finished',
    'server ip address could not be found',
    '404 not found',
    '500 internal server error',
    '502 bad gateway',
    '503 service unavailable',
    '504 gateway timeout'
  ];

  if (!currentUrl || currentUrl.startsWith('chrome-error://')) {
    failValidation(phase + ': browser reached error page at ' + currentUrl);
  }
  if (response && typeof response.status === 'function' && response.status() >= 400) {
    failValidation(phase + ': unexpected HTTP status ' + response.status() + ' at ' + currentUrl);
  }
  const marker = markers.find(marker => combined.includes(marker));
  if (marker) {
    failValidation(phase + ': detected error marker "' + marker + '" at ' + currentUrl);
  }
}

async function findElement(page, selector, timeout) {
  timeout = timeout || 3000;
  try {
    const locator = selector.startsWith('text=') || selector.startsWith('aria-label=')
      ? page.locator(selector)
      : page.locator('css=' + selector);
    await locator.first().waitFor({ state: 'visible', timeout });
    return locator.first();
  } catch (e) {
    return null;
  }
}

async function executeScenarioStep(page, step) {
  const action = step.action;
  const selector = step.selector || null;
  const timeout = step.timeout || 3000;
  try {
    switch (action) {
      case 'scroll': {
        if (step.direction === 'to' && selector) {
          try {
            const el = await page.locator(selector).first();
            await el.scrollIntoViewIfNeeded({ timeout });
          } catch (e) {
            console.error('  [warn] scroll-to failed: ' + e.message);
          }
        } else {
          const amount = step.amount || 300;
          const dir = step.direction === 'up' ? -amount : amount;
          await page.evaluate((d) => window.scrollBy(0, d), dir);
        }
        break;
      }
      case 'click': {
        const el = await findElement(page, selector, timeout);
        if (el) {
          await el.click({ timeout });
        } else {
          console.error('  [warn] click target not found: ' + selector);
        }
        break;
      }
      case 'type': {
        const el = await findElement(page, selector, timeout);
        if (el) {
          if (step.clear !== false) {
            await el.fill('');
          }
          await el.type(step.value || '', { delay: step.delay || 30 });
        } else {
          console.error('  [warn] type target not found: ' + selector);
        }
        break;
      }
      case 'select': {
        const el = await findElement(page, selector, timeout);
        if (el) {
          await el.selectOption(step.value || '');
        } else {
          console.error('  [warn] select target not found: ' + selector);
        }
        break;
      }
      case 'wait': {
        if (step.selector) {
          const el = await findElement(page, step.selector, step.timeout || 5000);
          if (!el) {
            console.error('  [warn] wait-for-element timeout: ' + step.selector);
          }
        } else {
          await sleep(step.ms || 1000);
        }
        break;
      }
      case 'assert': {
        const el = await findElement(page, selector, timeout);
        if (!el) {
          console.error('  [assert] element not found: ' + selector);
          break;
        }
        if (step.text) {
          const text = await el.textContent();
          const match = (text || '').toLowerCase().includes(step.text.toLowerCase());
          if (!match) {
            console.error('  [assert] text mismatch: expected "' + step.text + '", got "' + (text || '').trim().slice(0, 100) + '"');
          }
        }
        if (step.visible === true) {
          const vis = await el.isVisible();
          if (!vis) {
            console.error('  [assert] element not visible: ' + selector);
          }
        }
        break;
      }
      case 'navigate': {
        try {
          const rawUrl = step.url || '/';
          const targetUrl = rawUrl.startsWith('http')
            ? rewriteLocalhostUrl(rawUrl, page.url())
            : new URL(rawUrl, page.url()).href;
          const response = await page.goto(targetUrl, { waitUntil: step.waitUntil || 'domcontentloaded', timeout });
          await validatePageState(page, response, 'scenario_navigate');
        } catch (e) {
          failValidation('scenario_navigate: ' + e.message);
        }
        break;
      }
      case 'hover': {
        const el = await findElement(page, selector, timeout);
        if (el) {
          await el.hover();
        } else {
          console.error('  [warn] hover target not found: ' + selector);
        }
        break;
      }
      case 'keypress': {
        if (selector) {
          const el = await findElement(page, selector, timeout);
          if (el) {
            await el.focus();
          }
        }
        await page.keyboard.press(step.key || 'Enter');
        break;
      }
      case 'screenshot': {
        const name = step.name || 'screenshot';
        const shotPath = '/tmp/koncerto-demo/scenario-' + name + '.png';
        try {
          if (step.selector) {
            const el = await page.locator(step.selector).first();
            await el.screenshot({ path: shotPath });
          } else {
            await page.screenshot({ path: shotPath, fullPage: true });
          }
          console.error('  [screenshot] saved: ' + shotPath);
        } catch (e) {
          console.error('  [warn] screenshot failed: ' + e.message);
        }
        break;
      }
      case 'wait_for_selector': {
        const el = await findElement(page, step.selector, step.timeout || 5000);
        if (!el) {
          console.error('  [warn] wait_for_selector timeout: ' + step.selector);
        }
        break;
      }
      case 'scroll_to': {
        if (step.selector) {
          try {
            const el = await page.locator(step.selector).first();
            await el.scrollIntoViewIfNeeded({ timeout: timeout || 5000 });
          } catch (e) {
            console.error('  [warn] scroll_to failed: ' + e.message);
          }
        }
        break;
      }
      case 'highlight': {
        if (step.selector) {
          try {
            await page.evaluate((sel) => {
              const el = document.querySelector(sel);
              if (el) {
                el.style.outline = '3px solid #ff6600';
                el.style.outlineOffset = '2px';
                el.style.backgroundColor = 'rgba(255, 102, 0, 0.1)';
              }
            }, step.selector);
          } catch (e) {
            console.error('  [warn] highlight failed: ' + e.message);
          }
        }
        break;
      }
      case 'set_viewport': {
        try {
          const w = parseInt(step.width || step.viewport_width || '1280', 10);
          const h = parseInt(step.height || step.viewport_height || '720', 10);
          await page.setViewportSize({ width: w, height: h });
          await page.waitForTimeout(500);
        } catch (e) {
          console.error('  [warn] set_viewport failed: ' + e.message);
        }
        break;
      }
      default:
        console.error('  [warn] unknown action: ' + action);
    }
  } catch (e) {
    console.error('  [warn] step failed (' + action + '): ' + e.message);
  }
}

function rewriteLocalhostUrl(rawUrl, currentPageUrl) {
  try {
    const parsed = new URL(rawUrl);
    if (parsed.hostname !== 'localhost') return rawUrl;
    const current = new URL(currentPageUrl);
    if (parsed.port === current.port) return rawUrl;
    const rewritten = current.origin + parsed.pathname + parsed.search + parsed.hash;
    console.error('  [navigate] rewriting ' + rawUrl + ' -> ' + rewritten);
    return rewritten;
  } catch (e) {
    console.error('  [warn] rewriteLocalhostUrl error: ' + e.message);
    return rawUrl;
  }
}

(async () => {
  const chromiumPath = process.env.PW_CHROMIUM_PATH || '/usr/bin/chromium-browser';
  const browser = await chromium.launch({
    headless: false,
    executablePath: chromiumPath,
    args: [
      '--no-sandbox',
      '--disable-gpu',
      '--disable-accelerated-2d-canvas',
      '--disable-setuid-sandbox',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-sync',
      '--disable-notifications',
      '--disable-infobars',
      '--no-zygote',
      '--disable-dev-shm-usage'
    ]
  });

  const contextOptions = {
    viewport: { width: 1280, height: 720 },
    ignoreHTTPSErrors: true
  };
  if (useNativeVideo) {
    if (!outputPath) {
      throw new Error('PW_OUTPUT_PATH is required in native video mode');
    }
    contextOptions.recordVideo = {
      dir: path.dirname(outputPath),
      size: { width: 1280, height: 720 }
    };
  }

  const context = await browser.newContext(contextOptions);

  const page = await context.newPage();
  const requestFailures = [];
  const pageErrors = [];
  page.on('requestfailed', request => {
    requestFailures.push((request.url() || '') + ' :: ' + request.failure().errorText);
  });
  page.on('pageerror', error => {
    pageErrors.push(error.message || String(error));
  });

  let initialResponse = null;
  try {
    initialResponse = await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
  } catch (e) {
    failValidation('initial_navigation: ' + e.message);
  }

  await page.waitForTimeout(1000);
  await validatePageState(page, initialResponse, 'initial_navigation');
  if (requestFailures.length > 0) {
    failValidation('initial_navigation: request failures: ' + requestFailures.slice(0, 3).join(' | '));
  }
  if (pageErrors.length > 0) {
    failValidation('initial_navigation: page errors: ' + pageErrors.slice(0, 3).join(' | '));
  }

  fs.writeFileSync(readyFile, 'READY');

  if (scenarioFile && fs.existsSync(scenarioFile)) {
    console.error('[scenario] loading: ' + scenarioFile);
    try {
      const raw = fs.readFileSync(scenarioFile, 'utf-8');
      const scenario = parseScenarioYaml(raw);
      if (scenario && scenario.steps && scenario.steps.length > 0) {
        console.error('[scenario] executing ' + scenario.steps.length + ' steps: ' + (scenario.description || ''));
        for (let i = 0; i < scenario.steps.length; i++) {
          const step = scenario.steps[i];
          const label = step.action + (step.selector ? ' (' + step.selector + ')' : '');
          console.error('[scenario] step ' + (i + 1) + '/' + scenario.steps.length + ': ' + label);
          await executeScenarioStep(page, step);
        }
        console.error('[scenario] all steps completed');
      } else {
        console.error('[scenario] empty or invalid scenario (no steps)');
      }
    } catch (e) {
      console.error('[scenario] parse/execution error: ' + (e.message || 'unknown') + ' (file: ' + scenarioFile + ')');
    }
    await validatePageState(page, null, 'post_scenario');
    if (requestFailures.length > 0) {
      failValidation('post_scenario: request failures: ' + requestFailures.slice(0, 3).join(' | '));
    }
    if (pageErrors.length > 0) {
      failValidation('post_scenario: page errors: ' + pageErrors.slice(0, 3).join(' | '));
    }
  }

  if (useNativeVideo) {
    await sleep(Math.max(maxDurationSeconds, 1) * 1000);
    const video = page.video();
    await page.close();
    await context.close();
    if (!video) {
      throw new Error('Playwright did not create a video artifact');
    }
    const recordedPath = await video.path();
    fs.copyFileSync(recordedPath, outputPath);
    await browser.close();
    return;
  }

  await new Promise(() => {});
})().catch(err => {
  console.error('FATAL: ' + err.message);
  process.exit(1);
});

function parseScenarioYaml(raw) {
  const lines = raw.split('\n');
  let inScenario = false;
  let yamlLines = [];
  let foundDemoScenario = false;
  for (const line of lines) {
    const trimmed = line.trimEnd();
    if (trimmed === 'demo_scenario:') {
      inScenario = true;
      foundDemoScenario = true;
      continue;
    }
    if (inScenario) {
      if (trimmed.startsWith('---')) {
        break;
      }
      if (trimmed.startsWith('```') && !trimmed.startsWith('```yaml')) {
        break;
      }
      yamlLines.push(line);
    }
  }
  if (!foundDemoScenario) {
    console.error('[scenario] missing required key: demo_scenario:');
    return null;
  }
  const yaml = yamlLines.join('\n');
  if (!yaml.trim()) {
    console.error('[scenario] demo_scenario section is empty (no steps defined)');
    return null;
  }
  try {
    const parsed = parseSimpleYaml(yaml);
    if (!parsed || !parsed.steps || parsed.steps.length === 0) {
      console.error('[scenario] parsed scenario has no valid steps — check indentation and format');
      return null;
    }
    return parsed;
  } catch (e) {
    console.error('[scenario] YAML parse error at line ~' + (e.lineNumber || '?') + ': ' + (e.message || 'unknown syntax'));
    return null;
  }
}

function parseSimpleYaml(yaml) {
  const result = { steps: [] };
  let currentStep = null;
  const lines = yaml.split('\n');
  let stepIndex = 0;
  for (let li = 0; li < lines.length; li++) {
    const line = lines[li];
    // Structural checks below (steps:, - action:, - key:value) match against the start
    // of the line, so leading indentation must be stripped too, not just trailing
    // whitespace — every real scenario is indented under demo_scenario: and steps:,
    // so trimEnd() alone left every step marker unmatched and steps silently empty.
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const indent = line.search(/\S/);
    if (indent < 0) continue;

    if (trimmed === 'steps:') {
      currentStep = null;
      continue;
    }

    if (trimmed.startsWith('- action:')) {
      const actionVal = trimmed.slice('- action:'.length).trim();
      if (!actionVal) {
        throw new Error('empty action at line ' + (li + 1) + ' — expected: - action: <name>');
      }
      currentStep = {};
      result.steps.push(currentStep);
      stepIndex = result.steps.length;
      currentStep.action = actionVal;
      continue;
    }

    if (currentStep && trimmed.startsWith('- ')) {
      currentStep = {};
      result.steps.push(currentStep);
      stepIndex = result.steps.length;
      const val = trimmed.slice(2).trim();
      const colonIdx = val.indexOf(':');
      if (colonIdx > 0) {
        currentStep[val.slice(0, colonIdx).trim()] = val.slice(colonIdx + 1).trim();
      } else {
        console.error('[scenario:warn] step ' + stepIndex + ', line ' + (li + 1) + ': list entry without key:value — "' + val + '"');
      }
      continue;
    }

    if (currentStep && trimmed.includes(':')) {
      const colonIdx = trimmed.indexOf(':');
      const key = trimmed.slice(0, colonIdx).trim();
      if (!key) {
        console.error('[scenario:warn] step ' + stepIndex + ', line ' + (li + 1) + ': empty key before colon');
        continue;
      }
      let val = trimmed.slice(colonIdx + 1).trim();
      if (key === 'description' && !currentStep.description) {
        result.description = val;
        continue;
      }
      if (key === 'action' && !currentStep.action) {
        currentStep.action = val;
        continue;
      }
      if (val.startsWith('"') && val.endsWith('"')) {
        // A YAML double-quoted scalar treats \" as an escaped literal quote, not two
        // characters — without unescaping, a selector like "[data-testid=\"foo\"]" keeps
        // its backslashes and becomes an invalid CSS selector that matches nothing, so
        // every step referencing it silently fails even though the value LOOKS right.
        val = val.slice(1, -1).replace(/\\"/g, '"').replace(/\\\\/g, '\\');
      } else if (val.startsWith("'") && val.endsWith("'")) {
        val = val.slice(1, -1);
      } else if (/^-?\d+$/.test(val)) {
        // Everything out of this parser is a string by default, but Playwright validates
        // numeric options (delay, timeout, ms, amount, width/height) strictly — passing a
        // numeric-looking string like "50" as `delay` throws "expected float, got string"
        // and the step never runs at all, rather than degrading gracefully like most
        // failures here do.
        currentStep[key] = Number(val);
        continue;
      }
      currentStep[key] = val;
      continue;
    }

    if (trimmed.startsWith('description:') && !result.description && !currentStep) {
      result.description = trimmed.slice('description:'.length).trim().replace(/^["']|["']$/g, '');
    }
  }
  if (result.steps.length === 0) {
    throw new Error('no action steps found — each step must start with - action: <type>');
  }
  return result;
}
"""
    }
}

private fun traceRecordingStep(
    traceDir: java.nio.file.Path,
    step: String,
    status: String,
    details: Map<String, String> = emptyMap()
) {
    try {
        val payload = buildString {
            append('{')
            append("\"timestamp\":\"")
            append(Instant.now().toString())
            append("\",\"step\":\"")
            append(step)
            append("\",\"status\":\"")
            append(status)
            append('"')
            details.forEach { (key, value) ->
                append(",\"")
                append(key)
                append("\":")
                append('"')
                append(value.replace("\"", "\\\""))
                append('"')
            }
            append('}')
        }
        RollingTraceFiles.append(traceDir, "demo-trace", payload)
    } catch (_: Exception) {
    }
}
