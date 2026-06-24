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
                val scenarioArg = if (config.scenarioPath.isNotBlank() && File(config.scenarioPath).exists()) config.scenarioPath else ""
                pwScript.writeText(PLAYWRIGHT_SCRIPT)
                pwScript.deleteOnExit()

                shellScript = File.createTempFile("pw-record-", ".sh")
                shellScript.writeText(buildShellScript(config, outputFile.absolutePath, pwScript.absolutePath, scenarioArg))
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

    private fun buildShellScript(config: RecordingConfig, outputPath: String, pwScriptPath: String, scenarioPath: String = ""): String = """#!/bin/bash
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

SCENARIO_ARGS=""
if [ -n "${scenarioPath}" ] && [ -f "${scenarioPath}" ]; then
  SCENARIO_ARGS="${scenarioPath}"
fi

node "${pwScriptPath}" "${config.targetUrl}" "${'$'}{READY_FILE}" ${'$'}{SCENARIO_ARGS} &
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
const path = require('path');

const url = process.argv[2];
const readyFile = process.argv[3];
const scenarioFile = process.argv[4];

if (!url || !readyFile) {
  console.error('Usage: pw-recorder.js <url> <ready-file> [scenario-file]');
  process.exit(1);
}

async function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
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
          const targetUrl = step.url.startsWith('http') ? step.url : new URL(step.url, page.url()).href;
          await page.goto(targetUrl, { waitUntil: step.waitUntil || 'domcontentloaded', timeout });
        } catch (e) {
          console.error('  [warn] navigation failed: ' + e.message);
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
      default:
        console.error('  [warn] unknown action: ' + action);
    }
  } catch (e) {
    console.error('  [warn] step failed (' + action + '): ' + e.message);
  }
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

  try {
    await page.goto(url, { waitUntil: 'networkidle', timeout: 15000 });
  } catch (e) {
    console.error('Navigation warning: ' + e.message);
  }

  await page.waitForTimeout(1000);

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
      console.error('[scenario] parse/execution error: ' + e.message);
    }
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
  for (const line of lines) {
    const trimmed = line.trimEnd();
    if (trimmed === 'demo_scenario:') {
      inScenario = true;
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
  const yaml = yamlLines.join('\n');
  if (!yaml.trim()) return null;
  try {
    const parsed = parseSimpleYaml(yaml);
    return parsed;
  } catch (e) {
    console.error('YAML parse error: ' + e.message);
    return null;
  }
}

function parseSimpleYaml(yaml) {
  const result = { steps: [] };
  let currentStep = null;
  const lines = yaml.split('\n');
  for (const line of lines) {
    const trimmed = line.trimEnd();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const indent = line.search(/\S/);
    if (indent < 0) continue;

    if (trimmed === 'steps:') {
      currentStep = null;
      continue;
    }

    if (trimmed.startsWith('- action:')) {
      currentStep = {};
      result.steps.push(currentStep);
      currentStep.action = trimmed.slice('- action:'.length).trim();
      continue;
    }

    if (currentStep && trimmed.startsWith('- ')) {
      currentStep = {};
      result.steps.push(currentStep);
      const val = trimmed.slice(2).trim();
      const colonIdx = val.indexOf(':');
      if (colonIdx > 0) {
        currentStep[val.slice(0, colonIdx).trim()] = val.slice(colonIdx + 1).trim();
      }
      continue;
    }

    if (currentStep && trimmed.includes(':')) {
      const colonIdx = trimmed.indexOf(':');
      const key = trimmed.slice(0, colonIdx).trim();
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
        val = val.slice(1, -1);
      } else if (val.startsWith("'") && val.endsWith("'")) {
        val = val.slice(1, -1);
      }
      currentStep[key] = val;
      continue;
    }

    if (trimmed.startsWith('description:') && !result.description && !currentStep) {
      result.description = trimmed.slice('description:'.length).trim().replace(/^["']|["']$/g, '');
    }
  }
  return result;
}
"""
    }
}
