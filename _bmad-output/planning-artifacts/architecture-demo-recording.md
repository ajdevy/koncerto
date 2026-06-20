---
stepsCompleted: [1, 2, 3, 4, 5]
inputDocuments:
  - "_bmad-output/planning-artifacts/prd-demo-recording.md"
  - "_bmad-output/planning-artifacts/project-brief.md"
  - "_bmad-output/planning-artifacts/architecture.md"
  - "_bmad-output/planning-artifacts/prd.md"
project_name: "Koncerto - Demo Recording Feature"
user_name: "aj"
date: "2026-06-20"
---

# Architecture Document: Koncerto Demo Recording Feature

**Version:** 1.0
**Date:** 2026-06-20
**Status:** Initial Draft

## Project Context Analysis

### Requirements Overview

**Functional Requirements (44 total):**
- Demo Recording (FR1–FR10): Cross-platform screen/terminal capture with configurable encoding
- Report Generation (FR11–FR17): HTML reports with video embed, timeline, repro steps, API logs
- Storage & Management (FR18–FR23): R2 upload, presigned URLs, rolling cleanup, metadata tagging
- Integration (FR24–FR29): Event-driven trigger, Linear comment posting, manual re-trigger, metadata passthrough
- Error Handling (FR30–FR34): Exponential backoff retry, integrity validation, asciinema fallback, recovery
- Observability (FR35–FR38): Metrics (success rate, storage, latency), audit logging
- Configuration (FR39–FR44): YAML workflow config for quality, AI, storage, retry, trigger, enable/disable

**Non-Functional Requirements (16 total):**
- Performance: Setup/teardown < 10s, encoding < 2x duration, upload < 30s, total increase < 50%, report < 5s
- Security: Scoped presigned URLs, secret storage, opaque identifiers, auto-delete on archive
- Scalability: 3 concurrent recordings, R2 < 9 GB, artifact deletion without task impact
- Integration: Linear failure → local report fallback, R2 retry, pre-flight tool check

### Technical Constraints & Dependencies

- **Brownfield integration:** New module uses existing `koncerto-linear`, `koncerto-agent`, `koncerto-workspace` dependencies
- **No new external dependencies:** Uses existing Koncierto infrastructure (Spring Boot, Kotlin coroutines, YAML config)
- **Existing event system:** Trigger by listening to "code review passed" events
- **Existing Linear client:** Post comments and update ticket status via existing GraphQL client
- **Recording tools:** Playwright, asciinema (MVP); adb, xcrun, ffmpeg (Growth)
- **Storage:** Cloudflare R2 S3-compatible API
- **AI:** opencode free model (configurable)

### Cross-Cutting Concerns Identified

| Concern | Scope | Impact |
|---------|-------|--------|
| Recording abstraction | All platforms | Factory pattern to unify different recording backends |
| Storage abstraction | All artifacts | Single interface for upload, URL generation, cleanup |
| Event system integration | Linear + workflow engine | Consistent trigger/failure patterns |
| Error handling | All recording paths | Unified retry/fallback/blocked status |
| Observability | System-wide | Metrics emission, audit logging |
| Configuration | All components | YAML-driven, environment secret injection

## Core Architectural Decisions

### Data Architecture

- **Storage for task results:** SQLite (same as `koncerto-metrics` pattern)
- **Approach:** Custom repository interface + `SqliteDemoTaskRepository` impl using raw JDBC
- **Table:** `demo_tasks` — auto-created via `CREATE TABLE IF NOT EXISTS` on init
- **Thread safety:** `ReentrantLock` + `Dispatchers.IO`
- **Config path:** Default `~/.koncerto/demo.db`, overridable via `koncerto.demo.db.path`

### Demo Task Schema

| Column | Type | Purpose |
|--------|------|---------|
| `task_id` | TEXT PK | Linear task ID |
| `platform` | TEXT | web, terminal, android, ios, desktop |
| `status` | TEXT | pending, recording, encoding, uploading, uploaded, failed |
| `retry_count` | INTEGER | Current retry attempt |
| `error_message` | TEXT | Last error detail |
| `r2_url` | TEXT | Presigned URL of artifact |
| `r2_key` | TEXT | R2 object key |
| `file_size_bytes` | INTEGER | Artifact size |
| `duration_seconds` | INTEGER | Recording duration |
| `created_at` | TEXT | ISO-8601 timestamp |
| `updated_at` | TEXT | ISO-8601 timestamp |

### Recording Abstraction

- **Interface:** `DemoRecorder` with `suspend fun record(config: RecordingConfig): RecordingResult`
- **Factory:** `DemoRecorderFactory` selects implementation by platform + availability check (NFR16)
- **MVP implementations:** `PlaywrightRecorder` (web), `AsciinemaRecorder` (terminal)
- **Growth implementations:** `AdbRecorder` (Android), `XcrunRecorder` (iOS), `FfmpegRecorder` (desktop)
- **Platform detection:** Pre-flight check runs before task execution (NFR16)

### Storage Abstraction

- **Interface:** `DemoStorage` with `suspend fun upload(artifact: DemoArtifact): UploadResult`
- **Implementation:** `R2DemoStorage` using S3-compatible API
- **Methods:** upload, presign, delete, checkQuota, listOldest, keep
- **Rolling cleanup:** Pre-flight space check → delete oldest if needed → upload

### Event System Integration

- **Trigger:** Listen to existing "code review passed" event from `koncerto-orchestrator`
- **Manual trigger:** Linear "Record Demo" action via `koncerto-linear` GraphQL client
- **Output:** Post presigned URL as Linear comment; mark "blocked" on total failure
- **Error handling:** Local report fallback if Linear API unreachable (NFR14)

### Security & Secrets

- **Pattern:** Environment variable injection (existing Koncerto convention)
- **Secrets:** `R2_ENDPOINT`, `R2_ACCESS_KEY`, `R2_SECRET_KEY`, `LINEAR_API_TOKEN`
- **Presigned URLs:** Scoped to single artifact, opaque identifiers, cannot enumerate bucket

### Infrastructure & Deployment

- **Module:** New `koncerto-demo` module under existing Koncerto project
- **Dependencies:** `koncerto-linear`, `koncerto-agent`, `koncerto-workspace`, `org.xerial:sqlite-jdbc`
- **Recording tools:** Playwright + asciinema in MVP Docker image; adb/xcrun/ffmpeg added in Growth
- **Build:** Gradle convention plugin (follow existing module pattern)
- **Configuration:** YAML workflow config per PRD spec (FR39-FR44)

## Implementation Patterns & Consistency Rules

### Module Structure

```
koncerto-demo/
├── src/main/kotlin/com/flexsentlabs/koncerto/demo/
│   ├── config/
│   │   └── DemoRecordingConfig.kt
│   ├── model/
│   │   ├── DemoTask.kt
│   │   ├── RecordingConfig.kt
│   │   └── DemoResult.kt
│   ├── recorder/
│   │   ├── DemoRecorder.kt            (interface)
│   │   ├── DemoRecorderFactory.kt
│   │   ├── PlaywrightRecorder.kt
│   │   ├── AsciinemaRecorder.kt
│   │   ├── AdbRecorder.kt             (Growth)
│   │   ├── XcrunRecorder.kt           (Growth)
│   │   └── FfmpegRecorder.kt          (Growth)
│   ├── report/
│   │   └── DemoReportGenerator.kt
│   ├── storage/
│   │   ├── DemoStorage.kt             (interface)
│   │   └── R2DemoStorage.kt
│   ├── repository/
│   │   ├── DemoTaskRepository.kt      (interface)
│   │   └── SqliteDemoTaskRepository.kt
│   ├── integration/
│   │   └── DemoLinearIntegration.kt
│   ├── observability/
│   │   └── DemoMetrics.kt
│   └── DemoRecordingService.kt        (orchestrator)
├── build.gradle.kts
```

### Consistency Rules (Enforced for AI Agents)

1. **Repository owns all SQL** — No raw JDBC outside `SqliteDemoTaskRepository`. All queries go through the repository interface.
2. **Recorder interface** — Every platform implements `suspend fun record(config: RecordingConfig): DemoResult<RecordingResult>`. No direct tool calls from services.
3. **Result type** — Use sealed `DemoResult<T>` (Success / Failure with typed error) for all operations. No bare exceptions for flow control.
4. **Coroutine convention** — All async operations: `suspend fun`. Blocking operations: `withContext(Dispatchers.IO)`. Thread-safe state: `ReentrantLock`.
5. **Config-driven** — All recording quality settings come from YAML workflow. No hardcoded defaults in implementations.
6. **Linear reuse** — Use existing `koncerto-linear` module for all Linear API calls. No duplicate GraphQL client setup.
7. **Metrics reuse** — Use existing `koncerto-metrics` module patterns for observability (Micrometer gauges).
8. **Table auto-create** — `SqliteDemoTaskRepository.init()` creates `demo_tasks` table if not exists, same pattern as `SqliteMetricsRepository.init()`.

### Error Handling Patterns

```kotlin
sealed class DemoResult<out T> {
    data class Success<T>(val data: T) : DemoResult<T>()
    data class Failure(val error: DemoError) : DemoResult<Nothing>()
}

sealed class DemoError {
    data class RecordingFailed(val message: String, val cause: Throwable?) : DemoError()
    data class UploadFailed(val message: String, val cause: Throwable?) : DemoError()
    data class PlatformUnavailable(val platform: String) : DemoError()
    data class QuotaExceeded(val current: Long, val limit: Long) : DemoError()
    data class AiModelUnavailable(val model: String) : DemoError()
    data class LinearApiError(val message: String, val cause: Throwable?) : DemoError()
}
```

### Pre-Flight Check Order

Before any recording, run in order:
1. Platform tool available (e.g., `playwright --version`, `asciinema --version`)
2. R2 bucket accessible (signed request)
3. R2 quota available (check vs 9 GB threshold)
4. opencode model responsive (if AI features enabled)
5. Linear API accessible (list ticket labels)

If any check fails: log warning, skip if optional (AI), fail with `DemoError` if critical (platform, storage).

