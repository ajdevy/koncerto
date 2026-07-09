# Containerized Demo Recorder

## Problem

`PlaywrightRecorder` records demo videos by spawning Node + a real browser **directly on the Koncerto host**, using the operator's actual installed Google Chrome (`resolveChromiumExecutablePath()` prefers `/Applications/Google Chrome.app/...`). This means every automated demo recording visibly takes over the operator's own Chrome/desktop while it runs.

The reason it runs on the host at all: the target app's container publishes its port to `localhost` on the host (`ContainerInstance.baseUrl = "http://localhost:$hostPort"`), and `host.docker.internal` only resolves *from inside* a container, not the reverse — so today's code deliberately keeps the recorder on the host, where `localhost:<port>` just works.

## Approach

Run the recorder itself in a Docker container, on the **same Docker network** as the target app + its infra, addressed by container name at the internal port instead of `localhost:<hostPort>`. This removes the host-networking constraint that forced the native-host approach, and with it, the reason to touch the operator's real browser at all.

Docker-only: the host-Chrome path is deleted, not kept as a fallback. If Docker or the recorder image is unavailable, recording fails clearly rather than silently falling back to the host.

A key simplification: `PlaywrightRecorder` already has two recording code paths — `buildNativeShellScript` (macOS, Playwright's own `recordVideo`, the reason real Chrome.app was needed) and `buildShellScript` (Linux, Xvfb + `ffmpeg -f x11grab`, headless-friendly, already used in `Dockerfile.demo`). `useNativeVideoMode()` gates purely on `os.name` containing "mac". Inside a Linux container this is always false, so the container path automatically takes the **already-working** Xvfb+ffmpeg branch — no new recording mechanism needed, only a new *invocation* mechanism.

## Components

**New: `Dockerfile.recorder`** — a lean image containing only what recording needs: `node`, `chromium` (any build — no longer needs to be the real Google Chrome, since the macOS-native-video requirement no longer applies), `ffmpeg`, `xvfb`, and Chromium's runtime deps (`nss`, `freetype`, `harfbuzz`, `ttf-freefont`), plus the `playwright` npm package. Mirrors the equivalent subset of the existing `Dockerfile.demo`, which already proves this package set works for recording. No JAR, no `docker-cli`, no `git`/`gh` — this container's only job is running the extracted `PLAYWRIGHT_SCRIPT` against a URL and producing a `.webm`.

Built once and cached locally (tagged, e.g. `koncerto-recorder:latest`), matching the existing pattern for target-app images — rebuilt only if `Dockerfile.recorder`'s content hash changes, not on every recording.

**`ContainerLifecycleManager.kt` / `TargetProjectDeployer.kt`** — `ContainerInstance` and `DeployResult` currently only carry the host-facing URL (`"http://localhost:$hostPort"`). The container name, network, and internal container port are already known at container-creation time (`tryRunContainer`) but not surfaced. Add an internal-network URL (`http://<containerName>:<containerPort>`) alongside the existing host-facing one. The recorder uses the internal URL; PR comments, manual browsing, and everything else keep using the host-facing URL — no behavior change there.

**`PlaywrightRecorder.kt` — invocation rewrite:**
- `record()` no longer spawns a local `bash`/`node` subprocess. Instead: ensure the recorder image exists (build-once-cache-reuse, as above), then `docker run --rm --network <target's network> -v <scenario-dir>:/scenario:ro -v <output-dir>:/output <recorder-image> node pw-recorder.js <internal-target-url> /output/ready.flag /scenario/scenario.yaml`.
- The existing ready-marker-file / output-video-file handshake is conceptually unchanged, just relocated into the bind-mounted host directory instead of a bare OS temp file, so `waitForFile` / `traceRecordingStep` mostly carry over as-is.
- **Deleted as dead code once the container is always Linux**: `resolveChromiumExecutablePath()`, `buildNativeShellScript()`, `useNativeVideoMode()`, the macOS-native branch entirely. `runCleanup()`'s host-side `pkill` calls (`pw-recorder`, `chrome.*--no-sandbox`, `Xvfb :99`) are replaced by `docker rm -f` on the recorder container — there's nothing left running on the host to kill.

**Testing** — `PlaywrightRecorderTest.kt`'s `testRecordProcessBuilder` seam (currently fakes the subprocess) is replaced by an equivalent seam over the `docker run` invocation, so the test suite stays fully docker-free, matching the existing convention elsewhere in the codebase (`ContainerLifecycleManagerTest`, etc. use real/fake docker seams, not live daemons, for anything not explicitly marked as an integration test).

## Error handling & edge cases

- **Docker unavailable** (daemon down) → recording fails immediately with a clear, attributable error. No fallback to host Chrome.
- **Recorder image build fails** (bad Dockerfile, base-image pull failure) → fails at the "ensure image exists" step, distinct from a recording-content failure, so the demo auto-recovery loop (from the secrets-injection feature) doesn't mistake an infra problem for a fixable scenario/code problem and burn a recovery cycle on it.
- **Missing internal network URL** on `DeployResult` → fail fast with a descriptive error rather than silently falling back to `localhost` (which would just reintroduce today's problem in reverse).
- **Orphaned recorder containers** — `docker run --rm` self-cleans on normal exit/crash; folded into the existing orphan-sweep's `koncerto-demo-*` naming convention as a safety net, consistent with how target-app containers are already swept.

## Out of scope

- Changing the target-app deployment/network-creation logic itself (Dockerfile vs. compose detection, port allocation) — untouched.
- Multi-platform recorder support (Firefox/WebKit) — Chromium only, matching today's behavior.
- Pre-built/published recorder image via CI — build-once-and-cache locally, per the "Image build" decision.
