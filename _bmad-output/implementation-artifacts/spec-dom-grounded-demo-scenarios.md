---
title: 'DOM-grounded demo scenario generation'
type: 'feature'
created: '2026-07-12'
status: 'done'
context: []
baseline_commit: '694ce9c13e1f2ee8ff5dd7a847b226b1018a4c0f'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `DemoScenarioGenerator` authors browser scenarios from the ticket text alone, before the target app is deployed, so it hallucinates selectors, CTA text, and routes. On FLE-52 the first step clicked `text=Начать бесплатно` (real CTA is `Начать настройку`, a `mailto:` link); the real form lives at `GET /login` with testids `send-code-button`/`verify-button`/`code-input`. Nothing matched, the recorder sat on the landing page for 120s, and the emailed-code login was never performed on camera.

**Approach:** After the target app deploys and its URL is known, crawl the live app to extract a compact DOM inventory (reachable routes and, per route, `data-testid`s, form fields, button/link text, headings). Feed that inventory into scenario generation so the LLM uses only real selectors and navigates to real routes. Generation moves to after deploy+crawl; if the crawl yields nothing, generation proceeds ungrounded exactly as today.

## Boundaries & Constraints

**Always:** The crawl is best-effort — any crawl failure/empty result logs and falls through to ungrounded generation; the demo pipeline must never be blocked by crawling. Bound prompt growth: cap the inventory to a small route count and a total byte size. The crawl runs in the same ephemeral, `--rm` container pattern the recorder already uses (resolve container name + network from the internal target URL). Never emit credential values into the inventory. Maintain 100% line coverage on new non-container code.

**Ask First:** Changing the recovery/retry contract of `runDemoWithRecovery` beyond relocating generation into it. Adding new runtime deps to the recorder image (Node + Playwright + python3 already present — none should be needed).

**Never:** No headless real-browser crawl on the host (must be in-container against the internal URL). No authenticated/state-changing crawling — GET-only page loads, no form submission during the crawl. No scope creep into changing the scenario YAML schema or the recorder executor.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Grounded happy path | Deploy ok, crawl returns inventory with `/login` + testids | `generate` called with non-null `domInventory`; prompt contains the real routes/testids | N/A |
| Crawl empty | Deploy ok, crawl returns `[]` or unparseable | `generate` called with `domInventory=null`; log `dom_inventory_empty` | Fall through ungrounded |
| Crawl throws | Deploy ok, container/crawl errors | Same as empty; log `dom_inventory_failed` with reason | Swallow, continue |
| Deploy failed | No target URL | No crawl attempted; existing deploy-failure path unchanged | N/A |
| Oversized inventory | Many routes/elements | Inventory truncated to the route cap and byte cap before prompting | N/A |

</frozen-after-approval>

## Code Map

- `koncerto-demo/src/main/kotlin/com/flexsentlabs/koncerto/demo/recorder/DomInventoryCrawler.kt` -- NEW: runs a Playwright crawl script in-container, returns compact JSON inventory string (or null); mirrors `PlaywrightRecorder` container/network resolution + test seam.
- `koncerto-demo/src/main/kotlin/com/flexsentlabs/koncerto/demo/recorder/PlaywrightRecorder.kt` -- reference for container-name/network resolution, `--rm` run, and the `testRecordProcessBuilder` seam to copy.
- `koncerto-orchestrator/.../DemoScenarioGenerator.kt` -- add `domInventory: String?` to `generate`, `repair`, `buildPrompt`, `buildRepairPrompt`; prompt gains a "Real UI inventory — use ONLY these selectors/routes" section.
- `koncerto-orchestrator/.../AutoReviewOrchestrator.kt` -- relocate generation: drop pre-deploy `generate` (~line 250); in `runDemoWithRecovery` after `deployTargetProject` ok (~line 339) call the crawler, then `generate(..., domInventory)`; thread `domInventory` into the recovery `repair` call (~line 405).
- `scripts/coverage-badge.py` -- add `DomInventoryCrawler` to `EXCLUDED_CLASS_PATTERNS` (container/OS-dependent), like `PlaywrightRecorder`.
- `koncerto-app/.../Beans.kt` -- wire the crawler into the orchestrator.

## Tasks & Acceptance

**Execution:**
- [x] `DomInventoryCrawler.kt` -- crawl the internal target URL: load `/`, collect same-origin `<a href>`, visit up to a capped set of unique routes (plus conventional auth paths if reachable), and per route extract url, headings, `data-testid`s, form field name/type, button/link text; emit compact JSON, truncated to route + byte caps. Container-run copied from `PlaywrightRecorder` with a `testCrawlProcessBuilder` seam. Pure helpers (`internal`) for capping/parsing.
- [x] `DomInventoryCrawlerTest.kt` -- cover the I/O matrix via the seam: valid inventory, empty, unparseable, thrown process, oversized-truncation; cover pure helpers 100%.
- [x] `DemoScenarioGenerator.kt` -- add `domInventory` param (default null) to `generate`/`repair`/`buildPrompt`/`buildRepairPrompt`; when non-null, inject the inventory with an instruction to use only those selectors/routes and to `navigate` to real routes rather than clicking to reach them.
- [x] `DemoScenarioGeneratorTest.kt` -- assert prompt includes the inventory section when provided and omits it when null; both `buildPrompt` and `buildRepairPrompt`.
- [x] `AutoReviewOrchestrator.kt` -- relocate generation after deploy+crawl; best-effort crawl with `dom_inventory_*` logging; thread inventory into recovery repair.
- [x] `AutoReviewOrchestratorTest.kt` -- cover: crawl-ok grounded generate, crawl-empty/failed ungrounded fallback, deploy-failed no-crawl.
- [x] `DemoScenarioGenerator.kt` (`runGitDiff`) -- fix the silent no-op: `git diff main...HEAD` fails in target workspaces that only have `origin/main` (no local `main`). Fall back to `origin/main...HEAD` (and skip gracefully if neither ref resolves). This makes the existing pre-deploy diff-grounding actually fire.
- [x] `DemoScenarioGeneratorTest.kt` -- cover the ref-fallback: local `main` present, only `origin/main` present, neither present (returns null).
- [x] `scripts/coverage-badge.py` -- exclude `DomInventoryCrawler` container class.
- [x] `Beans.kt` -- construct and inject the crawler.

**Acceptance Criteria:**
- Given a deployed app exposing `/login` with real testids, when the pipeline runs, then the generated scenario `navigate`s to `/login` and references only inventory selectors (no hallucinated `request-code-btn`/`success-message`).
- Given the crawl fails or returns empty, when generation runs, then it proceeds ungrounded and the demo still records (no pipeline abort).
- Given the full build, when `./gradlew build jacocoTestReport -Pjacoco` and `scripts/coverage-badge.py` run, then line coverage stays 100.0%.

## Spec Change Log

- **2026-07-12 — root-cause finding during implementation:** The generator already grounds on real selectors/routes via `git diff main...HEAD`, but that command fails in target workspaces (`fatal: ambiguous argument 'main...HEAD'` — only `origin/main` exists), so `runGitDiff` returns null and grounding silently no-ops. This is the actual cause of the FLE-52 hallucination. **Amendment (human-approved scope add):** fix `runGitDiff` to fall back to `origin/main`, alongside the DOM crawl. The DOM crawl remains the durable live-DOM grounding; the git fix restores the cheap pre-deploy grounding. KEEP: both grounding sources feed the same prompt sections (Real Selectors / Real Routes / DOM inventory).

## Design Notes

Two-pass, honoring the frozen "ungrounded exactly as today" fallback: the pre-deploy `generate` stays (it drives the scenario-exhausted and missing-secrets gates and is now diff-grounded by the `origin/main` fix). After deploy, `runDemoWithRecovery` crawls the live app and, if the inventory is non-empty, calls `generate` again with `domInventory` — `saveScenario` overwrites the same issue-keyed files, so the record callback and repair loop transparently pick up the grounded scenario. If the crawl fails/empty, the pre-deploy scenario stands. The recovery `repair` call also receives `domInventory`.

Inventory shape (compact, capped ~4KB), example:
```json
[{"route":"/login","headings":["Вход в PromoMesh"],
  "testids":["email-input","send-code-button","code-input","verify-button"],
  "fields":[{"name":"email","type":"email"}],
  "buttons":["Отправить код","Подтвердить"]}]
```

## Verification

**Commands:**
- `./gradlew build jacocoTestReport -Pjacoco --no-daemon` -- expected: BUILD SUCCESSFUL (Telegram tests may fail offline — environmental, ignore).
- `python3 scripts/coverage-badge.py` -- expected: `Lines: N/N = 100.0%`.

**Manual checks:**
- Re-run FLE-52 through koncerto; inspect the saved scenario at `/tmp/koncerto-demo/FLE-52-scenario.yaml` — first interactive step is `navigate` to `/login` and every selector appears in the crawl inventory. Recording shows the code entered and the logged-in heading reached.
