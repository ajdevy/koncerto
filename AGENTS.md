# Koncerto Session Memory

## Aggregate Line Coverage 100% (CI)
- **Threshold**: `scripts/coverage-badge.py` enforces **100.0%** aggregate line coverage (`round(line_pct, 1) < 100.0` fails CI).
- **Verify locally**: `./gradlew test jacocoTestReport -Pjacoco && python3 scripts/coverage-badge.py`
- **Counted scope**: Non-excluded production classes after lambda/test-seam adjustment (`$default`, `invokeSuspend`, `getTest`/`setTest`).
- **Integration exclusions** (`_INTEGRATION_CLASS_EXCLUSIONS`): Docker deploy/runtime, Spring `Beans`, API login flows, `Orchestrator` tick loop, etc. — OS/process wiring not suited to unit coverage.
- **Other exclusions**: CLI entrypoints, Playwright/OS recorders, compiler-generated synthetics (`$lambda$`, `DefaultAgentRunner$`, …), serialization DTO companions.
- **History**: #15 raised coverage to 99%; remaining gaps closed in #16 (combined with FLE-52). This PR documents the 100% policy explicitly.

## Prompt Resolution Fix (FLE-51 e2e)
- **Root cause**: Stage `prompt` field stores a file path (`prompts/implement.md`) but is never resolved — codex receives the literal string `"prompts/implement.md"` as its prompt.
- **Fix**: Added `WorkflowCache.resolvePrompt()` that reads prompt file content if the prompt value resolves to an existing file.
- **Files**: `WorkflowCache.kt` (added `workflowDir`, `setWorkflowDir()`, `resolvePrompt()`), `DispatchService.kt` (resolves prompt before rendering), `AutoReviewOrchestrator.kt` (resolves review prompt), `Beans.kt` (wires cache).

## Race Condition in Dispatch Flow
- **Root cause**: `state.releaseClaim` ran *before* `handleNormalCompletion`, so the poller re-dispatched the same issue during the auto-review window (~20-60s), exhausting retries and moving to Blocked state.
- **Fix**: Moved `state.releaseClaim` into a `try/finally` block *after* `handleNormalCompletion`.
- **Defense**: Added "In Progress" safety-net stage that runs claude and transitions to "In Review".

## Auto-Review Parser Fix
- **Problem**: Parser checked `hasNonZeroCritical()` — any claude output listing critical items (even as non-blocking findings) failed the review.
- **Fix**: Only check for explicit `❌ FAIL` in claude output.

## Review Prompt Update
- **Change**: Clarified `❌ FAIL` is only for production-blocking issues (data loss, crashes, security vulns). Non-blocking findings should be warnings/suggestions.

## PR Comment Posting
- Added `postDetailedReviewAsPrComment()` to `AutoReviewOrchestrator.kt` — reads `.review-output-detailed` (backed up before auto-review overwrites it) and posts it via `gh pr comment`.

## PR Comment Cleanup (format & noise)
- **Problem**: Claude config errors ("Claude configuration file not found at: ...") from stderr leaked into review output via `redirectErrorStream(true)`. Claude's conversational preamble ("Now I have all the information I need...") preceded the actual review. No agent/model attribution in the comment.
- **Fixes**:
  - `ClaudeReviewRuntime.kt`: Filter config error lines from output before writing to `.review-output`
  - `prompts/review.md`: Tell claude "Start directly with the review verdict — no preamble, no thinking aloud"
  - `AutoReviewOrchestrator.kt` `postDetailedReviewAsPrComment()`: Strip config errors + preamble (belt-and-suspenders); add header `"### Claude Review #N (model)"` with sequence counter and model name

## Override `onCompleteState` Fix
- **Problem**: When `resolveStageOverride` returned `"in review"`, `prepareDispatch` hardcoded `onCompleteState = "In Review"`, causing the issue to transition from Todo → In Review → (second dispatch) → Ready for Human Review — wasting a full In Review cycle.
- **Fix**: Only override `onCompleteState` to `"In Review"` when the override target is NOT `"in review"`. If the override already targets `"in review"`, use the stage's original `onCompleteState` (`"Ready for Human Review"`).
- **Files**: `DispatchService.kt` line 412-416.

## Demo Recording Pipeline
- **Root cause**: `PlaywrightRecorder.isAvailable()` only checked `which node` (passed), but `require('playwright')` failed because the npm module wasn't in `NODE_PATH`. `record()` immediately failed with exit code ≠ 0. `DemoEventListener.onReviewPassed` silently discarded the failure.
- **Fixes**:
  - `Dockerfile`: Installed `chromium`, `nss`, `freetype`, `harfbuzz`, `ffmpeg` Alpine packages + `playwright` npm module; set `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH`; installed `playwright` in WORKDIR (not `-g`) for correct module resolution
  - `PlaywrightRecorder.kt`: Changed `headless: false` → `headless: true` (no display server in Docker); added explicit `executablePath` from env var; hardened `isAvailable()` to check `require('playwright')` resolves
  - **Config**: Added `targetUrl` field to `DemoRecordingConfig`, `DemoConfig`, and WORKFLOW.md `demo_recording.target_url: $DEMO_TARGET_URL`; wired through Beans.kt into `RecordingConfig`
- **Dependencies** (env vars needed by user):
  - `$DEMO_TARGET_URL` — URL of deployed app for Playwright to record
  - `$R2_ENDPOINT`, `$R2_ACCESS_KEY`, `$R2_SECRET_KEY`, `$R2_BUCKET`, `$R2_PUBLIC_URL_BASE` — R2/S3-compatible storage for demo uploads

## Demo URL in PR Comment
- **Problem**: Demo recording URL was only reported to Linear (via `LinearReportPublisher`), never posted to the GitHub PR. The flow ran `postDetailedReviewAsPrComment` first, then `onReviewPassed` — so the demo hadn't even started when the comment was posted.
- **Fix**: Swapped order — demo recording runs FIRST, then review comment is posted with the URL appended.
  - Changed `onReviewPassed` callback type from `suspend (Issue) -> Unit` to `suspend (Issue, String?) -> String?` (returns demo URL or null, accepts deploy target URL)
  - `DemoEventListener.onReviewPassed` now extracts `recordingUrl` from the completed task
  - `postDetailedReviewAsPrComment` appends `🎥 [Watch Demo Recording](<url>)` when URL is available
- **Flow**: review_passed → demo recording (120s Playwright capture + R2 upload) → PR comment with review + demo link → state transition

## Target Project Auto-Deploy for Demo Recording
- **Goal**: Auto-detect or create Docker config for the target project (PromoMesh) and spin it up after review passes for demo recording.
- **Components** (new `koncerto-deploy` module):
  - `DockerConfigDetector` — detects `docker-compose.yml` or `Dockerfile` in project root
  - `FrameworkDetector` — detects Spring Boot (build.gradle/pom.xml), Node (package.json), Python (requirements.txt), Go (go.mod)
  - `DockerfileGenerator` — generates an idiomatic multi-stage Dockerfile per framework
  - `TargetProjectDeployer` — orchestrates detection → build → run → health check
  - `ContainerLifecycleManager` — Docker build/run/health/log lifecycle, port range allocation
  - `DemoFailureReporter` — posts formatted error + logs to PR when deploy fails
- **Flow**: review_passed → `TargetProjectDeployer.deploy()` → if Docker config exists, build+run on free port → override `demo_recording.target_url` with container URL → demo recording → PR comment with demo link
- **If no Docker config**: generates a temporary `Dockerfile.koncerto` from framework detection and uses it (deleted after build)
- **Existing PR detection**: `GitHubPRQueryImpl` queries `gh pr list` for open PRs with docker labels (`docker-setup`, `infrastructure`) or files matching Docker paths — if found with passing checks, reuses that branch
- **Config** (WORKFLOW.md): `target_project_deploy.enabled`, `trigger`, `health_check_timeout_sec`, `port_range`
- **Env var `$DEMO_TARGET_URL`**: Now optional — if target project deploy succeeds, it's overridden with the container URL
- **Files**:
  - `koncerto-deploy/` (new module) — all deploy components
  - `koncerto-orchestrator/.../AutoReviewOrchestrator.kt` — calls deployer after review passes, before demo
  - `koncerto-demo/.../DemoRecordingService.kt` — accepts `targetUrl` override via `pendingTargetUrlOverride`
  - `koncerto-demo/.../DemoEventListener.kt` — passes `targetUrl` to recording service
  - `koncerto-app/.../Beans.kt` — wires deploy beans, `parseRepoFullName()` from GIT_REMOTE_URL
  - `koncerto-app/build.gradle.kts`, `koncerto-orchestrator/build.gradle.kts` — added `:koncerto-deploy` dependency

## Target Project Deploy Fixes (Session 2026-06-23)
### Issues Fixed
- **docker-compose not installed** — Added `docker-compose` to Alpine apk in Dockerfile
- **PR number hardcoded to 0** — Added `resolvePrNumber()` in `AutoReviewOrchestrator` that runs `gh pr view --json number`
- **Infra-only compose (no web app)** — `deployWithCompose()` detects infra-only compose, starts infra, falls through to app build on compose network
- **Compose port conflicts** — Consistent project name `-p koncerto-demo` with `down --remove-orphans` before each start
- **`pyproject.toml` not detected** — Added to `FrameworkDetector.hasPython()`
- **Python run command wrong** — `detectPythonRunCmd()` detects uvicorn + FastAPI app variable from `main.py`
- **Docker tag uppercase** — Added `.lowercase()` to tag generation for branch names with uppercase
- **Compose structured log pollution** — `resolveComposeNetwork()` filters `time="..."` lines, uses `HostConfig.NetworkMode`

### Verified End-to-End (FLE-75 PR#31)
```
dispatch → codex → PR → review_passed → deploy_compose_infra_only
→ docker_build_ok → container_started(32768) → deploy_success
→ pr_review_comment_posted → state="Ready for Human Review"
```

## Demo Recording R2 Upload SigV4 Fix (Session 2026-06-24)
### Issues Fixed
- **SigV4 canonical header ordering**: `buildSigV4AuthHeader()` inserted `host` first into `allHeaders`, but SigV4 requires **alphabetical** sorting. `host` appeared before `content-md5`/`content-type` in canonical headers → signature mismatch (403). Fixed by switching `joinToString`/`keys.joinToString` to `.sortedBy { it.key }`/`.sorted()`.
  - **Files**: `R2DemoStorage.kt:192-193`
- **`x-amz-tagging` not supported by R2**: R2 returns 501 `NotImplemented` for tagging header. Removed the `x-amz-tagging` header from the upload request entirely.
  - **Files**: `R2DemoStorage.kt:57-60` (removed)
- **`$R2_BUCKET` env var not resolved**: `r2Bucket` field in `parseDemoRecordingConfig` lacked `resolveEnvRef()`, so the literal string `$R2_BUCKET` was used as bucket name → `%24R2_BUCKET` in URL path.
  - **Files**: `ServiceConfig.kt:359` (added `resolveEnvRef()`)

### Root cause chain for R2 upload failure
1. SigV4 canonical headers not sorted → 403 SignatureDoesNotMatch
2. After fixing header sort → 501 NotImplemented (x-amz-tagging unsupported by R2)
3. After removing tagging header → 403 SignatureDoesNotMatch (bucket resolved to literal `$R2_BUCKET`)
4. After adding `resolveEnvRef()` for bucket → HTTP 200, upload succeeds

### Verified End-to-End (FLE-51 PR#23)
```
dispatch → codex → review_passed → deploy(port=32772)
→ Playwright recording(15s test) → ffmpeg → SigV4 upload to R2 → 200 OK
→ PR comment with 🎥 [Watch Demo Recording](presigned-url)
→ state="Ready for Human Review"
```

## Playwright Recorder Rewrite: Embedded Node.js Script (Session 2026-06-24)
### Problem
- Raw `chromium-browser` + `sleep 5` approach produced Chromium sign-in screen instead of app content
- Xvfb leaked between retry attempts: `set -e` exited the shell script before `kill` cleanup lines; faulty quoting in `runCleanup()` (`Runtime.exec("pkill -f 'Xvfb :99'")` split into wrong tokens) meant pkill never matched
- Content validation was impossible with raw browser approach
- Missing `executablePath: '/usr/bin/chromium-browser'` caused Node.js Playwright to fail (no bundled Chromium installed)

### Fixes
- **PlaywrightRecorder.kt** — rewritten `record()` to use an embedded Node.js Playwright script (stored in companion object, written to temp file) instead of raw chromium-browser
- **PLAYWRIGHT_SCRIPT** — Node.js script: launches Chromium via `chromium.launch({executablePath:'/usr/bin/chromium-browser', headless:false})`, navigates to URL with `waitUntil: 'networkidle'` and 15s timeout, validates page content (rejects chrome:// URLs, sign-in titles, empty <body> < 20 chars), writes "READY" to temp file on success
- **Shell script** — uses `trap cleanup EXIT` to guarantee Xvfb + Node.js kill on any exit path; `2>/dev/null` on Xvfb stderr to suppress `(EE)` noise; ready-file polling loop (30s timeout)
- **runCleanup()** — switched from `Runtime.exec(String)` to `Runtime.exec(arrayOf(...))` via ProcessBuilder-compatible array args for correct pkill matching
- **isAvailable()** — added `chromium-browser` binary check

### Files
- `koncerto-demo/.../recorder/PlaywrightRecorder.kt` — main changes (rewrote record(), buildShellScript(), runCleanup(), PLAYWRIGHT_SCRIPT, isAvailable())

### Verified End-to-End (FLE-51 re-dispatch)
```
dispatch → codex → PR → review_passed → deploy(32774)
→ Playwright recording Xvfb:99 + Chromium → ffmpeg 120s capture
→ R2 upload → PR comment with 🎥 demo URL
→ state="Ready for Human Review"
```

## Known Limitations
- Demo recording URL is a presigned URL (effectively permanent, 10yr TTL). For production, set `R2_PUBLIC_URL_BASE` to use public URLs.
- `gh_exit_1` "PR already exists" is cosmetic (safety-net stage clashes with codex PR)

## Pipeline Artifact Gitignore Fix (Session 2026-07-01)
### Problem
- `GitWorkflow.commitAndPush()` ran `git add -A`, staging Koncerto pipeline files (`.koncerto/*-trace-*.jsonl`, `.review-*`) into target-project PRs — e.g. PromoMesh PR#35 showed only orchestration artifacts, no app code.
- `.review-*` files were deleted after review but `.koncerto/*.jsonl` trace logs remained tracked once committed.

### Fix
- **`KoncertoArtifactIgnore.kt`** — defines gitignore block + `ensureGitignore()` / `untrackArtifacts()` helpers.
- **`GitWorkflow.commitAndPush()`** — ensures gitignore, `git rm --cached` on artifacts, then `git add -A`.
- **`WorkspaceManager.ensureWorkspace()`** / **`GitWorkflow.createBranch()`** — append gitignore block on workspace setup.
- **BMAD docs** — `_bmad/bmm/architecture/issue-lifecycle-state-machine.md`, `story-implementation.md`, `developer.md` document the contract.
- **`prompts/implement.md`** — instructs agents never to commit pipeline artifacts.

## gh pr comment Ghost Posting Fix (Session 2026-06-24)
### Root cause
- `postDetailedReviewAsPrComment` ran `gh pr comment --body` *without* `--repo` or PR number, relying on the current git branch to infer the target. On at least one run (15:06), exit code 0 was returned but the comment never appeared on the PR — the git state in the workspace didn't match what `gh` expected for branch-based inference.
- The `deployRepoFullName` resolved by `parseRepoFullName(config)` returned the **koncerto** repo (from `GIT_REMOTE_URL`), not the target project (PromoMesh).

### Fixes
- **`resolveRepoFullName(workspacePath)`** — new helper that reads `<workspace>/.git/config` to extract `owner/repo` from the origin remote URL (the actual target project)
- **`postDetailedReviewAsPrComment()`** — now uses `gh pr comment <PR#> --repo <repo> --body-file <file>` instead of `--body` with inferred PR. Writes body to `.review-body.txt`, deletes after posting.
- **`resolvePrNumber()`** — now passes `--repo <repo>` explicitly
- **`deployTargetProject()`** — prefers `resolveRepoFullName(ws.path)` over `deployRepoFullName`
- **Debug logging** — added `pr_comment_debug` (body length, demo URL) and captures `comment_url` from `gh` output

### Files
- `AutoReviewOrchestrator.kt` — all changes above

## Orphaned Demo Container Cleanup (Session 2026-06-25)
### Problem
- `TargetProjectDeployer.deploy()` started containers for the target project, but **nothing ever stopped them**. Postgres, Redis, and app containers + Docker images accumulated on the host permanently.

### Fix
- Added `cleanup(config: DeployConfig)` to `TargetProjectDeployer` that:
  - Finds & force-removes all containers by image tag (`docker rm -f $(docker ps -a -q --filter ancestor=<tag>)`)
  - Removes the Docker image (`docker rmi -f <tag>`)
  - Runs `docker compose -p koncerto-demo down --remove-orphans --volumes` if `docker-compose.yml` exists in the project path
- `AutoReviewOrchestrator.onCodingComplete()` now calls `cleanupDemoDeploy()` **after** demo recording + PR comment posting
- `DeployResult` now carries `isCompose` and `tag` fields for clean reconciliation

### Files
- `TargetProjectDeployer.kt` — `cleanup()`, updated `DeployResult`
- `AutoReviewOrchestrator.kt` — `cleanupDemoDeploy()`, `DeployResult` import
