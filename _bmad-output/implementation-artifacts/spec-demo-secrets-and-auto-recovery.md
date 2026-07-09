---
title: 'Demo Secret Injection + Fail-Fast Preflight + Auto-Recovery Loop'
type: 'feature'
created: '2026-07-05'
baseline_commit: 3ac9a1a
status: 'done'
context:
  - '{project-root}/_bmad-output/implementation-artifacts/stories/story-blocked-state-comment.md'
  - '{project-root}/prompts/demo-scenario.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Demo recordings cannot use target-project secrets (e.g. `BREVO_API_KEY`) — the deploy container starts with no env vars, so happy-path flows that call external services fail and the demo only shows an error. There is also no early validation: if a required secret is not configured, Koncerto still runs the whole agent + review + deploy pipeline before failing at record time. And any demo failure immediately blocks the ticket with no attempt to self-heal.

**Approach:** Three linked changes. (1) **Fail-fast preflight** at dispatch start: detect the target's required env vars ecosystem-aware, and if any required secret is not provided, block the ticket immediately with a comment naming exactly what is missing — before any implementation runs. (2) **Secret injection**: pass a per-project secrets file into the demo container via `DeployConfig.envVars` → `docker run -e`. (3) **Auto-recovery loop**: wrap the post-review demo sub-pipeline so that on failure it repairs the scenario first (no re-review), escalates to a target code fix + re-review if needed, re-records, and blocks only after 3 exhausted cycles.

## Boundaries & Constraints

**Always:**
- Preflight runs at dispatch start (before the coding agent runs). Required-secret detection is deterministic and ecosystem-aware: env templates (`.env.example/.sample/.template/.dist`) — **empty value → required, any non-empty value → has a default**; Spring `application*.{properties,yml}` — `${VAR}` without `:default` → required; compose `environment:` — entry without a default → required. Union across whatever conventions are present. No detectable declarations → empty set → no gate.
- Missing required secrets → post a Linear comment naming each missing key and where to add it, THEN transition to Blocked (best-effort, comment before transition), and skip the agent run.
- Secret values are injected only into the demo container's env and are masked (fixed short prefix + `…`) in every log line, trace, and comment.
- Recovery loop is capped at 3 demo→fix cycles per issue, counted in `RuntimeState` by issue id. Scenario repair does NOT re-run review; a code-fix escalation DOES re-run the existing review gate and re-records only on `ReviewDecision.Pass`.
- Exhaustion reuses `handleDemoRecordingFailure`: best-effort comment before Blocked, mirroring `story-blocked-state-comment`.
- New code carries unit tests for every I/O-matrix row; 100% coverage on new code.

**Ask First:**
- A code-fix dispatch whose agent fails to build the target or touches deploy/CI config — halt, don't loop.
- Any change to the Blocked-state meaning or the review-attempt counter.

**Never:**
- Store secret values in this spec, config block, logs, traces, PR comments, or committed files.
- Deep source-code scanning for env usage (e.g. `os.environ.get`) — out of scope; detection is declaration-file based.
- Record without a scenario; exceed 3 recovery cycles; let a code-fix escalation bypass review.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Preflight pass | Required vars all present in secrets file | Dispatch proceeds normally | N/A |
| Preflight missing | Required var absent/empty in secrets file | Comment lists missing keys; ticket → Blocked; agent NOT run | Comment+transition best-effort, logged |
| No declarations | No env template / all vars have defaults | Empty required set; dispatch proceeds | Log `demo_secrets_none` |
| Secret injection | Secrets file has `BREVO_API_KEY=…` | `docker run` gets `-e BREVO_API_KEY=…`; happy path serves | N/A |
| Repair fixes demo | Cycle-1 record fails; scenario repair yields runnable scenario | Cycle-2 records; no re-review; pipeline passes | N/A |
| Code fix + re-review | Repair insufficient; code fix dispatched; review passes | Demo re-recorded; pass on success | Re-review fail = one spent cycle |
| Exhaustion | 3 cycles fail | Comment then Blocked | Best-effort, each logged |
| Masking | Any log/trace/comment during deploy/failure | Secret values masked, never full | N/A |

</frozen-after-approval>

## Code Map

- `koncerto-deploy/.../SecretRequirementDetector.kt` (new) -- ecosystem-aware scan for required env vars; pluggable per-convention parsers
- `koncerto-orchestrator/.../DispatchService.kt` -- `prepareDispatch` (~588): preflight gate — compare required vs provided, block+comment+abort on missing
- `koncerto-deploy/.../TargetProjectDeployer.kt` -- `DeployConfig` add `envVars`; thread through `deploy`/`buildAndRun`/compose-app path
- `koncerto-deploy/.../ContainerLifecycleManager.kt` -- `tryRunContainer` emit `-e KEY=VAL`, mask in logs
- `koncerto-demo/.../config/DemoConfig.kt` + project config -- per-project `secretsFile` path; loader parses `KEY=VALUE`
- `koncerto-orchestrator/.../AutoReviewOrchestrator.kt` -- `deployTargetProject` fills `envVars`; new `runDemoWithRecovery` wraps demo section; reuse `handleDemoRecordingFailure`
- `koncerto-orchestrator/.../DemoScenarioGenerator.kt` -- add `repair(issue, workspace, priorScenario, failureReason)`
- `koncerto-orchestrator/.../RuntimeState.kt` -- add `demoRecoveryAttempts` counter map
- `koncerto-agent/.../AgentRunner.kt` -- `run(...)` for the code-fix dispatch

## Tasks & Acceptance

**Execution:**
- [x] `SecretRequirementDetector.kt` (new) -- detect required env vars across env-template/Spring/compose conventions; return empty when none -- required-key source
- [x] `DemoConfig.kt` + config parsing -- add per-project `secretsFile`; loader parses `KEY=VALUE`, skips blanks/`#` -- provided-secret source
- [x] `DispatchService.kt` (`prepareDispatch`) -- run preflight: if required−provided ≠ ∅, comment missing keys then Blocked, return null (abort) -- fail-fast gate
- [x] `TargetProjectDeployer.kt` -- add `envVars` to `DeployConfig`, thread into build/run/compose-app -- injection plumbing
- [x] `ContainerLifecycleManager.kt` -- emit `-e KEY=VAL`; mask values in logs -- container env
- [x] `AutoReviewOrchestrator.kt` (`deployTargetProject`) -- load secrets file, pass masked map to `DeployConfig.envVars` -- wires source→sink
- [x] `DemoScenarioGenerator.kt` -- add `repair(...)` -- scenario-first fix
- [x] `RuntimeState.kt` -- add `demoRecoveryAttempts` -- cycle counter
- [x] `AutoReviewOrchestrator.kt` -- `runDemoWithRecovery`: ≤3 cycles (record → repair scenario → else code fix via `AgentRunner` + re-run review → re-record); on exhaustion `handleDemoRecordingFailure` with cycle count; replace direct failure→Block -- recovery loop
- [x] Unit tests for every I/O-matrix row -- coverage (+ review-pass regression tests)

**Acceptance Criteria:**
- Given a required secret absent at dispatch start, when the ticket is picked up, then a comment naming the missing key is posted, the ticket goes Blocked, and no agent runs.
- Given all required secrets present, when a demo deploys, then `docker run` gets one `-e` per secret and no value appears unmasked in any log/trace/comment.
- Given a demo failure that scenario-repair resolves, when the loop runs, then it re-records without re-running review and passes.
- Given a demo failure needing a code fix, when dispatched, then review re-runs and re-record happens only if review passes.
- Given 3 exhausted cycles, when the loop ends, then a comment precedes the Blocked transition, both best-effort.
- Given no detectable env declarations, when dispatch runs, then no gate applies and `demo_secrets_none` is logged.

## Spec Change Log

- **Review pass 1 (2026-07-05).** Three independent adversarial reviewers (blind, edge-case, acceptance). All findings were **patch**-class — no `intent_gap`/`bad_spec`, so the frozen intent was untouched and no re-derivation was needed. Fixed in place:
  1. `.demo-fix-request` was written but never read → the escalated code-fix agent got no failure detail. Now folded into the next coding prompt (one-shot) via `DispatchService.consumeDemoFixRequest`.
  2. Dispatch-start preflight is a no-op on a brand-new ticket (repo not checked out yet). Added a **reliable second gate at the demo stage** (`executePostReviewPipeline`), where the workspace is populated, blocking directly (not via the recovery loop — an operator secret isn't agent-fixable).
  3. `demoRecoveryAttempts` was never reset → a once-blocked issue re-hit the cap on the first later failure. Now cleared on both Pass and block.
  4. Missing-secret preflight could re-comment every poll if the tracker transition failed. Now marks the issue blocked locally (`state.addBlocked`) so it isn't re-processed, and comments once.
  5. `SecretRequirementDetector` missed compose `${VAR:?err}` / `${VAR?err}` (the strongest "required" signal). Regex extended.
  6. `SecretsFile.mask` revealed 5-char secrets in full. Now hides anything under 8 chars entirely.
  - Added tests for each fix plus the previously-uncovered matrix rows (no-declarations proceed, secrets→envVars wiring). **Deferred** (pre-existing / prior-story, logged in `deferred-work.md`): `ensureNavigatesToRealRoute` indent-detection robustness. **Rejected:** `deploymentInFlight` stuck-flag (the try/finally in `cleanup` always resets it).

## Design Notes

Preflight, blocking, and cycle-count all mirror `story-blocked-state-comment`: comment BEFORE `updateIssueState`, wrapped in try/catch. `SecretRequirementDetector` is a set of small per-convention parsers behind one interface so new languages/OSes are added without touching callers; each returns declared-required var names, unioned.

Recovery control flow (as implemented): `runDemoWithRecovery` deploys once, then loops on the recording call. A recording failure increments the per-issue `demoRecoveryAttempts` counter. Scenario-first repair re-records against the SAME deployment in-loop (no re-deploy, no re-review). When scenario repair can't help, it escalates to a code fix WITHOUT recursion: it writes the failure to `.demo-fix-request` and returns `ReviewDecision.RetryWithCoding`, so the normal DispatchService coding+review loop re-runs (that loop is what actually invokes `AgentRunner.run`, and `prepareDispatch` folds the `.demo-fix-request` content into the coding prompt one-shot so the agent knows what to fix), and on a fresh review pass the pipeline re-enters `runDemoWithRecovery`. The persisted counter caps the whole thing at `MAX_DEMO_RECOVERY` = 3, after which `handleDemoRecordingFailure` posts a comment and blocks; the counter is cleared on both Pass and block. Masking helper: values under 8 chars are hidden entirely, otherwise first 4 chars + `…`. Per-project secrets path lives on `ProjectConfig.demoSecretsFile`. The missing-secret gate runs both at dispatch start (best-effort; a no-op until the repo is checked out) and — reliably — at the demo stage where the workspace is populated.

Known limitation: a pure-compose web app (served directly by a compose service, not `docker run`) doesn't receive `-e` injection — logged as `deploy_compose_env_not_injected`. Env injection covers Dockerfile builds and the compose-infra→app-build path (which PromoMesh uses).

## Verification

**Commands:**
- `./gradlew :koncerto-deploy:test :koncerto-orchestrator:test :koncerto-demo:test` -- expected: green incl. new tests
- `./gradlew test` -- expected: full suite green
- coverage task -- expected: 100% on new code

**Manual checks:**
- Configure the PromoMesh secrets file with `BREVO_API_KEY`; re-run FLE-52; confirm (a) preflight passes, (b) the recorded video shows the code-send step succeeding (no "Не удалось отправить код"), (c) the key is masked in logs/traces.
- Remove the key; re-run; confirm dispatch blocks immediately with a comment naming `BREVO_API_KEY` and no agent runs.

## Suggested Review Order

**Recovery loop (entry point)**

- Start here — the self-healing demo loop: deploy once, record, repair-or-escalate, cap at 3
  [`AutoReviewOrchestrator.kt:301`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt#L301)
- Counter cleared on both success and block so a later retry gets a fresh budget
  [`AutoReviewOrchestrator.kt:347`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt#L347)

**Fail-fast missing-secret gate**

- Dispatch-start preflight: block + comment + mark-blocked before any agent runs
  [`DispatchService.kt:596`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DispatchService.kt#L596)
- Reliable second gate at demo stage (workspace populated) — the one that actually fires for new tickets
  [`AutoReviewOrchestrator.kt:267`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt#L267)
- Escalation feedback folded into the next coding prompt, one-shot
  [`DispatchService.kt:663`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DispatchService.kt#L663)

**Secret detection & source**

- Ecosystem-aware required-var detector (env templates, Spring, compose incl. `${VAR:?}`)
  [`SecretRequirementDetector.kt:23`](../../koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/SecretRequirementDetector.kt#L23)
- Per-project secrets file loader + short-value-safe masking
  [`SecretsFile.kt:14`](../../koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/SecretsFile.kt#L14)
- Config surface for the secrets path
  [`ProjectConfig.kt:18`](../../koncerto-core/src/main/kotlin/com/flexsentlabs/koncerto/core/config/ProjectConfig.kt#L18)

**Secret injection into the demo container**

- `envVars` on the deploy config, threaded through to `docker run -e`
  [`TargetProjectDeployer.kt:34`](../../koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/TargetProjectDeployer.kt#L34)
- Testable argv builder that emits one `-e KEY=VAL` per secret
  [`ContainerLifecycleManager.kt:108`](../../koncerto-deploy/src/main/kotlin/com/flexsentlabs/koncerto/deploy/ContainerLifecycleManager.kt#L108)

**Supporting**

- Per-issue recovery counter
  [`RuntimeState.kt:67`](../../koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/RuntimeState.kt#L67)
- Tests: detector, secrets file, preflight, recovery loop, injection wiring — across the deploy/orchestrator test suites
