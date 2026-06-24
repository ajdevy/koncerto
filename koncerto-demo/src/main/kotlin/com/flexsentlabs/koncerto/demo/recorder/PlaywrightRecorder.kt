package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.RecordingConfig
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaywrightRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val xvfb = ProcessBuilder("which", "Xvfb").start()
            if (!(xvfb.waitFor(5, TimeUnit.SECONDS) && xvfb.exitValue() == 0)) return@withContext false
            val node = ProcessBuilder("which", "node").start()
            if (!(node.waitFor(5, TimeUnit.SECONDS) && node.exitValue() == 0)) return@withContext false
            val playwright = ProcessBuilder("node", "-e", "require('playwright')").start()
            if (!(playwright.waitFor(5, TimeUnit.SECONDS) && playwright.exitValue() == 0)) return@withContext false
            val chromium = ProcessBuilder("which", "chromium-browser").start()
            if (!(chromium.waitFor(5, TimeUnit.SECONDS) && chromium.exitValue() == 0)) return@withContext false
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
            try {
                if (!isAvailable()) {
                    return@withContext DemoResult.Failure(DemoError.RecorderNotAvailable("playwright"))
                }

                val startTime = System.currentTimeMillis()

                pwScript = File.createTempFile("pw-recorder-", ".js")
                pwScript.writeText(PLAYWRIGHT_SCRIPT)
                pwScript.deleteOnExit()

                shellScript = File.createTempFile("pw-record-", ".sh")
                shellScript.writeText(buildShellScript(config, outputFile.absolutePath, pwScript.absolutePath))
                shellScript.setExecutable(true)
                shellScript.deleteOnExit()

                val process = ProcessBuilder("bash", shellScript.absolutePath)
                    .redirectErrorStream(true)
                    .start()

                val maxWaitSec = config.maxDurationSeconds + 60L
                val completed = process.waitFor(maxWaitSec, TimeUnit.SECONDS)
                val durationMs = System.currentTimeMillis() - startTime

                if (!completed) {
                    process.destroyForcibly()
                    runCleanup()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Recording timed out"))
                    )
                }

                val exitCode = process.exitValue()
                val output = process.inputStream.bufferedReader().readText()

                if (exitCode == 2) {
                    runCleanup()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Content validation failed"))
                    )
                }

                if (exitCode != 0) {
                    runCleanup()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Recording exited with code $exitCode: $output"))
                    )
                }

                if (!outputFile.exists() || outputFile.length() == 0L) {
                    runCleanup()
                    return@withContext DemoResult.Failure(
                        DemoError.RecordingFailed(RuntimeException("Output file is empty or missing"))
                    )
                }

                DemoResult.Success(DemoRecorder.RecordingResult(
                    file = outputFile,
                    durationMs = durationMs,
                    fileSizeBytes = outputFile.length(),
                    format = "webm"
                ))
            } catch (e: Exception) {
                runCleanup()
                DemoResult.Failure(DemoError.RecordingFailed(e))
            } finally {
                pwScript?.delete()
                shellScript?.delete()
            }
        }

    private fun buildShellScript(config: RecordingConfig, outputPath: String, pwScriptPath: String): String = """#!/bin/bash
set -e
export DISPLAY=:99

READY_FILE=$(mktemp /tmp/pw-ready-XXXXXX)
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

node "${pwScriptPath}" "${config.targetUrl}" "${'$'}{READY_FILE}" &
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

ffmpeg -y -f x11grab -draw_mouse 0 -r ${config.frameRate} -s ${config.width}x${config.height} -i :99.0 \
  -c:v libvpx-vp9 -b:v 200k -t ${config.maxDurationSeconds} "${outputPath}" 2>/dev/null

exit ${'$'}?
"""

    private fun runCleanup() {
        try {
            val rt = Runtime.getRuntime()
            rt.exec(arrayOf("pkill", "-f", "pw-recorder"))
            rt.exec(arrayOf("pkill", "-f", "chrome.*--no-sandbox"))
            rt.exec(arrayOf("pkill", "-f", "Xvfb :99"))
        } catch (_: Exception) {}
    }

    companion object {
        private val PLAYWRIGHT_SCRIPT = """#!/usr/bin/env node
const { chromium } = require('playwright');
const fs = require('fs');

const url = process.argv[2];
const readyFile = process.argv[3];

if (!url || !readyFile) {
  console.error('Usage: pw-recorder.js <url> <ready-file>');
  process.exit(1);
}

(async () => {
  const browser = await chromium.launch({
    headless: false,
    executablePath: '/usr/bin/chromium-browser',
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

  const context = await browser.newContext({
    viewport: { width: 1280, height: 720 },
    ignoreHTTPSErrors: true
  });

  const page = await context.newPage();

  let pageLoaded = false;
  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
    pageLoaded = true;
  } catch (e) {
    console.error('Navigation warning: ' + e.message);
  }

  await page.waitForTimeout(3000);

  const title = await page.title();
  let bodyText = '';
  let currentUrl = '';
  try {
    bodyText = await page.evaluate(() => (document.body?.innerText || '').trim());
    currentUrl = page.url();
  } catch (e) {
    console.error('Page eval warning: ' + e.message);
  }

  const failures = [];

  if (currentUrl.startsWith('chrome://') || currentUrl.startsWith('about:')) {
    failures.push('redirected_to_internal_page=' + currentUrl);
  }

  if (!title || /sign\s*in/i.test(title) || /chromium/i.test(title) || title === 'about:blank') {
    failures.push('bad_title=' + (title || '(empty)'));
  }

  const textLen = (bodyText || '').length;
  if (textLen < 20) {
    failures.push('empty_body(' + textLen + 'chars)');
  }

  if (failures.length > 0) {
    console.error('VALIDATION_FAILED: ' + failures.join('; '));
    await browser.close();
    process.exit(2);
  }

  fs.writeFileSync(readyFile, 'READY');

  await new Promise(() => {});
})().catch(err => {
  console.error('FATAL: ' + err.message);
  process.exit(1);
});
"""
    }
}
