package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Crawls the live, deployed target app for a compact DOM inventory (reachable routes and, per
 * route, real `data-testid` values, form fields, button/link text and headings). That inventory
 * grounds demo-scenario generation so the model authors steps against selectors/routes that
 * actually exist instead of guessing.
 *
 * Runs Playwright in the same ephemeral `--rm` container pattern as [PlaywrightRecorder], joining
 * the target's Docker network so it can reach the internal `http://<container>:<port>` URL. The
 * crawl is GET-only and never submits forms — it observes, it does not act.
 *
 * Best-effort by contract: every failure path returns null so the caller falls back to ungrounded
 * generation and the demo is never blocked by crawling. Like the recorders, this container/OS-
 * dependent class is excluded from coverage aggregation; its pure helpers are still unit-tested.
 */
class DomInventoryCrawler(
    private val logger: StructuredLogger
) {
    /** Crawls [targetUrl] (an internal `http://<container>:<port>` URL) and returns a compact JSON
     *  inventory string, or null when crawling is impossible, fails, or yields nothing. */
    suspend fun crawl(targetUrl: String): String? = withContext(Dispatchers.IO) {
        val runDir = createRunDir()
        try {
            val useRealContainer = testCrawlProcessBuilder == null
            val containerName = if (useRealContainer) parseContainerName(targetUrl) else "test"
            if (useRealContainer && containerName == null) {
                logger.warn("dom_inventory_not_internal_url", mapOf("target_url" to targetUrl))
                return@withContext null
            }
            File(runDir, "crawl.js").writeText(CRAWL_SCRIPT)

            val pb = testCrawlProcessBuilder?.invoke(targetUrl) ?: run {
                val image = RecorderImage().ensureAvailable().getOrElse { e ->
                    logger.warn("dom_inventory_image_unavailable", mapOf("error" to (e.message ?: "unknown")))
                    return@withContext null
                }
                val network = resolveNetwork(requireNotNull(containerName)).getOrElse { e ->
                    logger.warn("dom_inventory_network_unresolved", mapOf("error" to (e.message ?: "unknown")))
                    return@withContext null
                }
                ProcessBuilder(
                    listOf(
                        "docker", "run", "--rm",
                        "--network", network,
                        "-v", "${runDir.absolutePath}:/work",
                        "-e", "TARGET_URL=$targetUrl",
                        "-e", "MAX_ROUTES=$MAX_ROUTES",
                        "-e", "PW_CHROMIUM_PATH=/usr/bin/chromium-browser",
                        image, "node", "/work/crawl.js"
                    )
                )
                    // stdout carries the JSON inventory; discard stderr (crawl logs) so it can't
                    // corrupt the JSON and can't fill the pipe buffer and deadlock the wait.
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
            }

            val process = pb.start()
            val stdout = process.inputStream.bufferedReader().readText()
            val completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                logger.warn("dom_inventory_timeout", mapOf("target_url" to targetUrl))
                return@withContext null
            }
            if (process.exitValue() != 0) {
                logger.warn("dom_inventory_crawl_failed", mapOf("exit" to process.exitValue().toString()))
                return@withContext null
            }
            val inventory = extractInventoryJson(stdout)
            if (inventory == null) {
                logger.info("dom_inventory_empty", mapOf("target_url" to targetUrl))
                return@withContext null
            }
            capInventory(inventory)
        } catch (e: Exception) {
            logger.warn("dom_inventory_error", mapOf("error" to (e.message ?: "unknown")))
            null
        } finally {
            runDir.deleteRecursively()
        }
    }

    /** Same shape as PlaywrightRecorder.parseContainerName — a host-facing/localhost URL yields
     *  null because the crawl container can't reach localhost:hostPort from its own namespace. */
    private fun parseContainerName(targetUrl: String): String? = try {
        java.net.URI(targetUrl).host?.takeIf { it.isNotBlank() && it != "localhost" }
    } catch (_: Exception) {
        null
    }

    private fun resolveNetwork(containerName: String): Result<String> = try {
        val pb = ProcessBuilder("docker", "inspect", containerName, "--format", "{{.HostConfig.NetworkMode}}")
            .redirectErrorStream(true)
        val p = pb.start()
        val output = p.inputStream.bufferedReader().readText().trim()
        val ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok || output.isBlank()) {
            Result.failure(RuntimeException("docker inspect failed for $containerName: $output"))
        } else {
            Result.success(output)
        }
    } catch (e: Exception) {
        Result.failure(RuntimeException("docker inspect error: ${e.message}"))
    }

    private fun createRunDir(): File {
        val dir = File(File(System.getProperty("user.home"), ".koncerto/demo-crawler-tmp"), java.util.UUID.randomUUID().toString())
        dir.mkdirs()
        return dir
    }

    /** Pulls the JSON array out of the crawl's stdout. Returns null for missing/empty (`[]`) output
     *  so an empty crawl is treated the same as no crawl (ungrounded fallback). */
    internal fun extractInventoryJson(raw: String): String? {
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val candidate = raw.substring(start, end + 1).trim()
        // "[]" (or a whitespace-only array) means the crawler reached the app but found nothing
        // worth grounding on — treat as empty rather than injecting an empty section.
        return candidate.takeIf { it.length > 2 && it.substring(1, it.length - 1).isNotBlank() }
    }

    /** Hard byte cap as a secondary guard (the crawl script already caps routes/elements). Trims
     *  back to the last complete route object and closes the array so the result stays readable. */
    internal fun capInventory(json: String, maxBytes: Int = MAX_BYTES): String {
        if (json.length <= maxBytes) return json
        val truncated = json.substring(0, maxBytes)
        val lastObjEnd = truncated.lastIndexOf("},")
        return if (lastObjEnd > 0) truncated.substring(0, lastObjEnd + 1) + "]" else truncated
    }

    companion object {
        /** Test seam: when set, replaces the docker crawl process with a caller-supplied one. */
        @JvmStatic
        var testCrawlProcessBuilder: ((String) -> ProcessBuilder)? = null

        private const val MAX_ROUTES = 8
        private const val MAX_BYTES = 4000
        private const val TIMEOUT_SECONDS = 90L

        private val CRAWL_SCRIPT = """#!/usr/bin/env node
const { chromium } = require('playwright');

const targetUrl = process.env.TARGET_URL;
const maxRoutes = parseInt(process.env.MAX_ROUTES || '8', 10);
// Conventional auth/entry routes worth probing even when nothing on the landing page links to
// them — the feature under demo is often a login/registration flow reached only by direct URL.
const CONVENTIONAL = ['/login', '/signin', '/sign-in', '/register', '/signup', '/auth/login'];

const cap = (arr, n) => arr.slice(0, n);
const clip = (s) => (s || '').replace(/\s+/g, ' ').trim().slice(0, 80);

async function inspect(page) {
  return await page.evaluate(() => {
    const uniq = (xs) => Array.from(new Set(xs.filter(Boolean)));
    const testids = uniq(Array.from(document.querySelectorAll('[data-testid]'))
      .map(e => e.getAttribute('data-testid')));
    const headings = Array.from(document.querySelectorAll('h1,h2,h3'))
      .map(e => (e.innerText || '').trim()).filter(Boolean);
    const fields = Array.from(document.querySelectorAll('input,select,textarea'))
      .map(e => ({ name: e.getAttribute('name') || e.getAttribute('id') || '', type: e.getAttribute('type') || e.tagName.toLowerCase() }))
      .filter(f => f.name);
    const buttons = Array.from(document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]'))
      .map(e => (e.innerText || e.value || '').trim()).filter(Boolean);
    const links = Array.from(document.querySelectorAll('a[href]'))
      .map(e => ({ text: (e.innerText || '').trim(), href: e.getAttribute('href') || '' }));
    return { testids, headings, fields, buttons, links };
  });
}

(async () => {
  const inventory = [];
  if (!targetUrl) { process.stdout.write('[]'); process.exit(0); }
  const chromiumPath = process.env.PW_CHROMIUM_PATH;
  const launchOptions = { headless: true, args: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'] };
  if (chromiumPath) launchOptions.executablePath = chromiumPath;
  let browser;
  try {
    browser = await chromium.launch(launchOptions);
  } catch (e) {
    // No usable browser — emit an empty inventory rather than failing the whole demo.
    process.stdout.write('[]');
    process.exit(0);
  }
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const origin = new URL(targetUrl).origin;

  const queue = ['/'];
  const seen = new Set();
  try {
    // Prime discovery from the landing page's own links before falling back to conventions.
    const first = await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => null);
    if (first) {
      const landing = await inspect(page);
      for (const l of landing.links) {
        try {
          const u = new URL(l.href, page.url());
          if (u.origin === origin && u.pathname && !queue.includes(u.pathname)) queue.push(u.pathname);
        } catch (e) { /* ignore malformed href */ }
      }
    }
    for (const c of CONVENTIONAL) if (!queue.includes(c)) queue.push(c);

    for (const route of queue) {
      if (inventory.length >= maxRoutes) break;
      if (seen.has(route)) continue;
      seen.add(route);
      const resp = await page.goto(origin + route, { waitUntil: 'domcontentloaded', timeout: 12000 }).catch(() => null);
      if (!resp || resp.status() >= 400) continue;
      const info = await inspect(page).catch(() => null);
      if (!info) continue;
      const hasSignal = info.testids.length || info.fields.length || info.buttons.length;
      if (!hasSignal && route !== '/') continue;
      inventory.push({
        route,
        headings: cap(info.headings.map(clip), 5),
        testids: cap(info.testids, 40),
        fields: cap(info.fields.map(f => ({ name: clip(f.name), type: clip(f.type) })), 20),
        buttons: cap(info.buttons.map(clip), 15)
      });
    }
  } catch (e) {
    // Whatever we gathered so far is still useful; emit it.
  } finally {
    try { await browser.close(); } catch (e) { /* ignore */ }
  }
  process.stdout.write(JSON.stringify(inventory));
  process.exit(0);
})();
"""
    }
}
