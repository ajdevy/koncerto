---
title: 'API demo scenario generation and platform routing'
type: 'feature'
created: '2026-08-25'
status: 'in-review' # draft | ready-for-dev | in-progress | in-review | done
context: []
baseline_commit: '23da048f5fcf8c74bcc75deba19e3529680f9ddb'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** `ApiRecorder` and `DemoPlatform.API` landed in 23da048, and `DemoRecordingService` already routes to the API recorder when an `-api-scenario.yaml` is staged — but nothing in the pipeline ever writes one. A backend-only ticket therefore still gets a browser recording of an app that has no UI, and the API recorder is reachable only by an explicit platform argument or as the Playwright fallback.

**Approach:** After the target deploys, fetch a compacted OpenAPI inventory through a new injected seam, alongside the existing DOM crawl. A pure decision picks API when the live deployment shows no usable DOM but does publish an OpenAPI document. On API, generate a scenario from `prompts/demo-scenario-api.md` grounded on that inventory, stage it as `-api-scenario.yaml`, and remove the stale browser scenario so the recorder routes to API on its own.

## Boundaries & Constraints

**Always:** "No browsable UI" means the crawled inventory carries no UI signal — the crawler emits a `/` entry unconditionally, so non-blank is NOT evidence of a UI. Scenario staging is atomic (temp file + `ATOMIC_MOVE`) and self-cleaning in both directions: whichever kind is chosen, the other kind's staged file is removed. Fail-open — any fetch, decision, or generation failure leaves the browser path behaving exactly as it does today; platform detection must never block a ticket. The seam is an injected `suspend` fn returning the **already-compacted** inventory (compaction lives in `koncerto-demo`), so `koncerto-orchestrator` gains no dependency on `koncerto-demo` — same style as `crawlDomInventory`. The spec fetch is GET-only. Decide from the *live deployment*, not from file extensions in the diff. Maintain 100% line coverage on new non-container code.

**Ask First:** Changing the retry/repair contract of `runDemoWithRecovery` beyond adding the decision. Changing the `api_scenario` YAML schema or the recorder executor. Making API generation mandatory (i.e. blocking a ticket when it fails).

**Never:** No host-side HTTP to the target — the spec fetch runs in-container against the internal URL. No new runtime dependencies in the recorder image (`curl`/`xterm` already added in 23da048). No deleting the browser scenario until an API scenario has been successfully written. No scope creep into Android/iOS platforms.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| API chosen | DOM inventory shows no UI *signal* (no testids/fields/buttons on any route), OpenAPI inventory present | `generateApi` runs; `-api-scenario.yaml` staged atomically; browser scenario deleted; log `demo_scenario_api` | N/A |
| Browser kept — UI exists | DOM inventory carries UI signal, OpenAPI inventory present | Existing DOM-grounded browser path, unchanged; any stale `-api-scenario.yaml` is cleared | N/A |
| Browser kept — no spec | DOM inventory null, OpenAPI inventory null | Existing ungrounded browser path, unchanged | N/A |
| Spec fetch throws | Seam raises | Treated as "no spec"; log `openapi_inventory_failed` | Swallow, continue |
| API generation fails | Model exhausts all fallback passes (returns null, does not throw) | Browser scenario left in place and NOT deleted; log `demo_scenario_api_exhausted`; PR comment carries a note that the API demo could not be produced | Fall through to browser path |
| Deploy failed | No internal target URL | No fetch, no decision; existing deploy-failure path unchanged | N/A |
| API recording fails | Staged API scenario recorded, recorder reports failure | Recovery repairs the **API** scenario (not the browser one); `scenarioPath` follows the repaired file | Escalate to code fix as today |
| Stale API scenario | `-api-scenario.yaml` left by an earlier pass, verdict now BROWSER | Stale API scenario deleted before recording, so the fresh browser scenario is what runs | N/A |

</frozen-after-approval>

## Code Map

- `koncerto-orchestrator/.../DemoPlatformDecision.kt` -- NEW: pure `decide(domInventory, openApiInventory) -> BROWSER | API`. No I/O, fully unit-testable.
- `koncerto-orchestrator/.../DemoScenarioGenerator.kt` -- add `generateApi(issue, workspace, credentialKeys, openApiInventory)` mirroring `generate()`'s free-model fallback; parameterize `extractScenarioBlock(raw, rootKey = "demo_scenario")` so `api_scenario` reuses the three tolerated formats; add `saveApiScenario` (writes both id- and identifier-keyed `-api-scenario.yaml`) and `deleteBrowserScenario`. Skip `ensureNavigatesToRealRoute` — browser-only.
- `koncerto-orchestrator/.../AutoReviewOrchestrator.kt` -- add `fetchOpenApiInventory: (suspend (internalUrl: String) -> String?)? = null` ctor seam; in `runDemoWithRecovery`, after the existing crawl, apply the decision and on API generate + stage + delete the browser scenario.
- `koncerto-app/.../Beans.kt` -- wire the seam to `OpenApiSpecFetcher(logger).fetch(url)` piped through `OpenApiInventory.compact`.
- `prompts/demo-scenario-api.md` -- existing system prompt consumed by `generateApi`; no change.
- `koncerto-orchestrator/.../DemoScenarioGenerator.kt` -- also needs `repairApi` (API-kind repair), `deleteApiScenario`, atomic staging, and a usability check that the extracted block contains at least one request before it is staged.
- `koncerto-app/.../DemoRecordingTrigger.kt` -- second recorder-registration site; `ApiRecorder` is missing there, so the manual trigger can never route to API.
- `koncerto-demo/.../recorder/api/ApiScriptBuilder.kt` -- `{{KEY}}` currently resolves only to `SAVED_*`; injected credentials arrive as plain env vars, so an authenticated demo references an unset variable under `set -u`.
- `koncerto-demo/.../recorder/api/DemoScenarioFiles.kt` -- the `-api-scenario.yaml` naming convention the generator must match.

## Tasks & Acceptance

**Execution:**
- [x] `DemoPlatformDecision.kt` -- pure decision + rationale string for logging -- keeps routing testable without touching the orchestrator.
- [x] `DemoPlatformDecisionTest.kt` -- cover all four input combinations plus blank-vs-null inventories.
- [x] `DemoScenarioGenerator.kt` -- `generateApi`, `rootKey`-parameterized `extractScenarioBlock`, `saveApiScenario`, `deleteBrowserScenario`; API prompt loads `prompts/demo-scenario-api.md` and injects the OpenAPI inventory as ground truth plus the credential-key section.
- [x] `DemoScenarioGeneratorTest.kt` -- `api_scenario` extraction across all three formats; prompt contains the inventory and the API system prompt; save writes both keys; delete removes both browser files; existing `demo_scenario` behaviour unregressed.
- [x] `AutoReviewOrchestrator.kt` -- seam + decision wiring per the I/O matrix, all paths fail-open.
- [x] `AutoReviewOrchestratorTest.kt` -- cover every row of the I/O matrix.
- [x] `Beans.kt` -- construct and inject the seam.

- [x] `DemoPlatformDecision.kt` -- decide on UI *signal*, not blankness; keep it pure.
- [x] `DemoScenarioGenerator.kt` -- `repairApi`, `deleteApiScenario`, atomic staging with rollback, block-usability check.
- [x] `AutoReviewOrchestrator.kt` -- clear the opposite scenario kind on both paths; API-aware repair; log/trace the exhausted case; carry a PR note when the API demo could not be produced.
- [x] `DemoRecordingTrigger.kt` -- register `ApiRecorder` so the manual trigger can route to API.
- [x] `ApiScriptBuilder.kt` -- resolve `{{KEY}}` to a saved value, then an injected credential, then empty.
- [x] Tests -- assert the mandated log/trace events, the new matrix rows, and exact model-call counts.

**Acceptance Criteria:**
- Given a deployed API-only target and a ticket, when the post-review pipeline runs, then an `-api-scenario.yaml` exists in the staging dir, no `-scenario.yaml` remains for that issue, and the recording task's platform resolves to `API`.
- Given a deployed target with a browsable UI, when the pipeline runs, then no `-api-scenario.yaml` is written and the browser scenario is used exactly as before this change.
- Given the OpenAPI seam is absent or throws, when the pipeline runs, then behaviour is byte-for-byte the current browser path and the ticket is never blocked by platform detection.

## Spec Change Log

### 2026-08-25 — review round 1 (bad_spec + 2 intent gaps)

**Triggering findings.** Three adversarial reviewers (blind / edge-case / acceptance) produced 19 deduplicated findings. Verified against source, the load-bearing ones were: (a) `DomInventoryCrawler` pushes the `/` route unconditionally, so `hasUi = inventory non-blank` is true for virtually every REST service and the API path would never fire; (b) `runDemoWithRecovery` repairs with browser-only `repair()`, which on an API ticket recreates the just-deleted browser scenario, never fixes the API one, and re-records the identical failing scenario until the cycle cap blocks the ticket; (c) no `deleteApiScenario` exists, so one API pass pins the issue to the API recorder permanently; (d) two sequential `writeString` calls are non-atomic and race the reader's existence check; (e) `generateApi` returning null is invisible and mislabelled as `dom_inventory_empty`; (f) `DemoRecordingTrigger` never registers `ApiRecorder`.

**Amended.** Frozen matrix rows 1-3 were renegotiated with the human: the API predicate is now "no UI signal" rather than "inventory blank", and generation exhaustion falls back to the browser path *with a PR note* rather than silently. Two matrix rows were added (API recording failure, stale API scenario). Boundaries gained the atomicity and bidirectional-cleanup invariants. Code Map and Tasks gained the sites the original spec never named.

**Known-bad state avoided.** The unamended spec produced a feature that (1) almost never activates on its own target case, (2) cannot recover from any API recording failure, and (3) permanently mis-routes an issue after a single API pass.

**KEEP.** These survived review and must be preserved through re-derivation: the injected `suspend` seam returning an already-compacted inventory (keeps `koncerto-orchestrator` free of a `koncerto-demo` dependency — auditor verified clean); the pure, total `DemoPlatformDecision` with a rationale string; deleting the browser scenario strictly *after* the API scenario is written; the three independent `runCatching` fail-open wrappers; and the `rootKey`-parameterized `extractScenarioBlock` reusing one parser for both kinds.

## Verification

**Commands:**
- `./gradlew test --continue` -- expected: green except the 3 known docker-daemon failures in `koncerto-deploy`.
- `./gradlew test jacocoTestReport -Pjacoco && python3 scripts/coverage-badge.py` -- expected: no new uncovered lines attributable to this change (compare against the branch baseline, which has 10 pre-existing environment-dependent gaps in `DemoRecordingService.kt`). Restore `.badges/` afterwards.
