---
stepsCompleted: ["step-01-validate-prerequisites"]
inputDocuments:
  - "_bmad-output/planning-artifacts/prd-demo-recording.md"
  - "_bmad-output/planning-artifacts/architecture-demo-recording.md"
project_name: "Koncerto - Demo Recording Feature"
user_name: "aj"
date: "2026-06-20"
---

# Epics and Stories: Koncerto Demo Recording Feature

## Overview

Epic and story breakdown for the Demo Recording feature, organized by user value delivery.

## Requirements Inventory

### Functional Requirements

**Demo Recording (FR1-FR10)**
- FR1: System can record an end-to-end screen video of a completed AI agent task
- FR2: System can record a terminal session (commands + output) for technical tasks
- FR3: System can specify recording platform per task (web, terminal, mobile, desktop)
- FR4: System can encode screen recordings at minimal readable quality (720p@10fps VP9)
- FR5: System can capture mouse cursor and click indicators during recording
- FR6: System can apply dirty-rectangle optimization to skip static frames
- FR7: System can add timestamp overlay to video recording
- FR8: System can record Application Under Test on Android devices via ADB
- FR9: System can record Application Under Test on iOS devices via xcrun
- FR10: System can record Application Under Test on desktop (Linux/macOS/Windows)

**Report Generation (FR11-FR17)**
- FR11: System can generate an HTML report for each recorded demo
- FR12: HTML report embeds or links to the recorded video
- FR13: HTML report includes a text timeline of events with timestamps
- FR14: HTML report includes step-by-step reproduction instructions
- FR15: HTML report includes structured logs of API calls and commands for technical tasks
- FR16: System can generate timeline annotations using configured AI model
- FR17: System can generate reproduction steps using configured AI model

**Storage & Management (FR18-FR23)**
- FR18: System can upload demo artifacts to Cloudflare R2 object storage
- FR19: System can generate long-lived presigned URLs for demo artifacts
- FR20: System can check available storage quota before recording
- FR21: System can delete oldest demo artifacts when storage space is needed
- FR22: System can tag R2 objects with task metadata (task ID, commit SHA, PR number, platform)
- FR23: System can "keep" specific demos to exclude them from rolling window cleanup

**Integration (FR24-FR29)**
- FR24: System can trigger demo recording when a PR review passes
- FR25: System can trigger demo recording via a Linear "Record Demo" action
- FR26: System can post a comment with the demo URL to the Linear ticket
- FR27: System can update Linear ticket status to "blocked" on total demo failure
- FR28: System can register demo recording as a configurable post-code-review workflow step
- FR29: System can pass task metadata (platform, app, test script) from Linear to the recorder

**Error Handling & Resiliency (FR30-FR34)**
- FR30: System can retry failed demo recording with exponential backoff (up to 3 attempts)
- FR31: System can validate recording integrity after stop (duration, size, corruption check)
- FR32: System can fall back to asciinema text recording when video recording fails
- FR33: System can report detailed error information on demo generation failure
- FR34: System can recover from partial recordings on platform disconnection

**Observability (FR35-FR38)**
- FR35: System can expose metrics: demo generation success rate, retry count, failure reasons
- FR36: System can expose metrics: R2 storage usage, average demo size, cleanup events
- FR37: System can expose metrics: demo generation latency (review pass to Linear comment)
- FR38: System can log detailed audit trail of recording, upload, and Linear comment events

**Configuration (FR39-FR44)**
- FR39: System can configure recording quality (resolution, fps, codec) via YAML workflow
- FR40: System can configure AI model (opencode free) and features (timeline, repro steps) via YAML workflow
- FR41: System can configure R2 endpoint, bucket, and credentials via environment or workflow
- FR42: System can configure retry policy (max attempts, backoff strategy) via YAML workflow
- FR43: System can configure trigger conditions (review passed, label present, manual) via YAML workflow
- FR44: System can enable or disable demo recording per workflow in YAML config

### Non-Functional Requirements

- NFR1: Recording setup and teardown complete within 10 seconds
- NFR2: Demo encoding completes within 2x recording duration
- NFR3: R2 upload completes within 30 seconds for average recording (< 50 MB)
- NFR4: Recording does not increase total task run time by more than 50%
- NFR5: HTML report generation completes within 5 seconds
- NFR6: Presigned URLs scoped to individual artifact, cannot enumerate bucket
- NFR7: R2 credentials stored as secret, not inline in workflow config
- NFR8: Linear API token stored as secret with minimum required permissions
- NFR9: Presigned URLs reference opaque identifiers, not internal task IDs
- NFR10: Recorded artifacts auto-removed when Linear ticket is archived
- NFR11: Supports up to 3 concurrent recordings without runner degradation
- NFR12: Rolling window cleanup maintains R2 usage below 9 GB
- NFR13: Demo artifacts deletable without impacting task execution
- NFR14: Linear API failures fall back to local report
- NFR15: R2 API errors retry with exponential backoff (3 attempts)
- NFR16: Recording tool availability check runs before task execution

### Additional Requirements (from Architecture)

- New `koncerto-demo` module following existing Gradle convention patterns
- SQLite for task results (custom repository, raw JDBC, auto-created `demo_tasks` table)
- `DemoRecorder` interface with factory pattern for platform-specific implementations
- `DemoStorage` interface with `R2DemoStorage` implementation
- Sealed `DemoResult<T>` with typed `DemoError` for all error handling
- Pre-flight check order: platform → R2 → quota → AI → Linear

## FR Coverage Map

- FR1 (record screen video): Epic 1
- FR2 (record terminal session): Epic 1
- FR3 (specify platform per task): Epic 1, Epic 4
- FR4 (720p@10fps VP9 encode): Epic 1
- FR5 (mouse cursor capture): Epic 1
- FR6 (dirty-rectangle optimization): Epic 1
- FR7 (timestamp overlay): Epic 1
- FR8 (Android recording): Epic 4
- FR9 (iOS recording): Epic 4
- FR10 (desktop recording): Epic 4
- FR11 (HTML report): Epic 1
- FR12 (embed/link video in report): Epic 1
- FR13 (timeline in report): Epic 1
- FR14 (reproduction steps in report): Epic 1
- FR15 (structured API logs in report): Epic 1
- FR16 (AI timeline annotations): Epic 5
- FR17 (AI repro steps): Epic 5
- FR18 (upload to R2): Epic 1
- FR19 (presigned URLs): Epic 1
- FR20 (check storage quota): Epic 3
- FR21 (delete oldest artifacts): Epic 3
- FR22 (tag R2 objects with metadata): Epic 1
- FR23 (keep specific demos): Epic 3
- FR24 (trigger on review pass): Epic 1
- FR25 (manual trigger via Linear): Epic 1
- FR26 (post comment to Linear): Epic 1
- FR27 (mark blocked on failure): Epic 2
- FR28 (configurable workflow step): Epic 2
- FR29 (pass task metadata): Epic 1
- FR30 (retry with backoff): Epic 2
- FR31 (integrity validation): Epic 2
- FR32 (asciinema fallback): Epic 2
- FR33 (error reporting): Epic 2
- FR34 (partial recording recovery): Epic 2
- FR35 (success rate metrics): Epic 3
- FR36 (storage usage metrics): Epic 3
- FR37 (latency metrics): Epic 3
- FR38 (audit logging): Epic 3
- FR39 (config quality via YAML): Epic 2
- FR40 (config AI model via YAML): Epic 2
- FR41 (config R2 via env/workflow): Epic 2
- FR42 (config retry via YAML): Epic 2
- FR43 (config trigger via YAML): Epic 2
- FR44 (enable/disable via YAML): Epic 2

## Epic List

### Epic 1: Core Web & Terminal Demo Recording
Reviewers can verify web UI changes and technical tasks through automatically recorded demos posted to Linear.

**FRs covered:** FR1–FR7, FR11–FR15, FR18–FR19, FR22, FR24–FR26, FR29
**Scope:** MVP
**Dependencies:** Existing Koncerto infrastructure (Linear client, event system)

### Epic 2: Reliable Recording Pipeline
The recording pipeline handles failures gracefully with exponential backoff retries, asciinema fallback, configurable settings, and proper error reporting.

**FRs covered:** FR27–FR28, FR30–FR34, FR39–FR44
**Scope:** MVP
**Dependencies:** Epic 1 (the pipeline needs something to fail)

### Epic 3: Observability & Demo Management
DevOps can monitor success rates, storage usage, and manage demo lifecycle including cleanup and manual re-triggers.

**FRs covered:** FR20–FR21, FR23, FR35–FR38
**Scope:** MVP (basic), Growth (dashboard)
**Dependencies:** Epic 1, Epic 2 (metrics need recording pipeline)

### Epic 4: Cross-Platform Recording
Teams using Android, iOS, or desktop applications can verify demos directly.

**FRs covered:** FR3, FR8–FR10
**Scope:** Growth
**Dependencies:** Epic 1 (uses same DemoRecorder interface)

### Epic 5: AI-Enhanced Reports & Demo Library
Reviewers get AI-generated timeline annotations and reproduction steps. Teams can search and compare historical demos.

**FRs covered:** FR16–FR17
**Scope:** Growth (AI), Vision (library)
**Dependencies:** Epic 1, Epic 2 (needs recording pipeline + storage)

## Epic 1: Core Web & Terminal Demo Recording

### Story 1.1: Module Setup

As a developer, I want a new `koncerto-demo` module with build configuration and package structure, so that all demo recording code has a home.

**Acceptance Criteria:**

- **Given** the Koncerto project root, **When** a developer lists modules, **Then** `koncerto-demo` appears as a Gradle submodule in `settings.gradle.kts`
- **Given** `koncerto-demo/build.gradle.kts`, **When** inspected, **Then** it declares dependencies on `koncerto-linear`, `koncerto-agent`, `koncerto-workspace`, and `org.xerial:sqlite-jdbc:3.46.1.3`
- **Given** the module package `com.flexsentlabs.koncerto.demo`, **When** created, **Then** it contains subpackages: `config`, `model`, `recorder`, `report`, `storage`, `repository`, `integration`, `observability`
- **Given** the Gradle build, **When** running `./gradlew :koncerto-demo:build`, **Then** it compiles successfully

### Story 1.2: Demo Task Model & Repository

As a developer, I want to persist demo task records in SQLite, so that I can track status, retries, and results across service restarts.

**Acceptance Criteria:**

- **Given** `SqliteDemoTaskRepository` initialized, **When** constructed, **Then** it creates a `demo_tasks` table with columns: `task_id`, `platform`, `status`, `retry_count`, `error_message`, `r2_url`, `r2_key`, `file_size_bytes`, `duration_seconds`, `created_at`, `updated_at`
- **Given** a `DemoTask`, **When** saved via `save(task)`, **Then** it is persisted and retrievable via `findById(task_id)`
- **Given** multiple tasks, **When** `findAll()` is called, **Then** all tasks are returned ordered by `created_at desc`
- **Given** a task with `status="failed"`, **When** `findByStatus("failed")` is called, **Then** it is returned
- **Given** concurrent writes, **When** multiple threads call `save()`, **Then** all operations complete without corruption (`ReentrantLock`)
- **Given** IO operations, **When** any repository method is called, **Then** it executes on `Dispatchers.IO`

### Story 1.3: DemoRecorder Interface + PlaywrightRecorder

As a reviewer, I want the system to record web UI interactions using Playwright, so that I can visually verify frontend changes.

**Acceptance Criteria:**

- **Given** `DemoRecorder` interface, **When** inspected, **Then** it defines `suspend fun record(config: RecordingConfig): DemoResult<RecordingResult>`
- **Given** `PlaywrightRecorder` implementing `DemoRecorder`, **When** `record()` is called, **Then** it launches a Playwright browser session
- **Given** a recording session, **When** completed, **Then** it produces a VP9-encoded WebM file at 720p@10fps
- **Given** a Playwright recording, **When** analyzed, **Then** it includes mouse cursor and click indicators (FR5)
- **Given** static frames during recording, **When** optimized, **Then** dirty-rectangle encoding skips unchanged regions (FR6)
- **Given** a recording, **When** finished, **Then** it includes a timestamp overlay in the video (FR7)
- **Given** the platform is unavailable, **When** pre-flight check runs, **Then** it returns `PlatformUnavailable`
- **Given** recording fails mid-way, **When** interrupted, **Then** partial recording is saved as failure artifact

### Story 1.4: AsciinemaRecorder

As a developer, I want the system to record terminal sessions using asciinema, so that technical tasks have text-based demos.

**Acceptance Criteria:**

- **Given** `AsciinemaRecorder` implementing `DemoRecorder`, **When** `record()` is called, **Then** it runs asciinema to capture terminal output
- **Given** a terminal recording, **When** completed, **Then** it produces an asciicast v2 format file
- **Given** the HTML report step, **When** the asciicast is included, **Then** it renders as an interactive terminal embed
- **Given** asciinema is not installed, **When** pre-flight check runs, **Then** it returns `PlatformUnavailable`
- **Given** a terminal task with no visual UI, **When** platform detection runs, **Then** terminal is selected as recording platform
