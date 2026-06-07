# Symphony Kotlin Implementation - Design Document

**Date:** 2026-06-07
**Status:** Approved
**Target:** Kotlin/JVM with Spring Boot, full implementation of Symphony SPEC v1

## Overview

This document describes the design for **koncerto**, a Kotlin/JVM implementation of the OpenAI Symphony orchestration service. Symphony turns project work into isolated, autonomous implementation runs by polling an issue tracker (Linear), creating per-issue workspaces, and running coding agents (Codex) to handle the work.

The implementation follows the language-agnostic Symphony SPEC (v1) and aims to be a faithful, modular, production-ready reimplementation in Kotlin.

## Goals

- Full implementation of the Symphony SPEC (orchestrator, workspace manager, agent runner, Linear client, HTTP dashboard)
- Modular Spring Boot architecture with clear separation of concerns
- Kotlin coroutines for concurrency (lightweight, structured)
- Spring WebClient for HTTP/GraphQL calls to Linear
- Type-safe configuration with YAML front matter parsing
- Testable design with unit and integration tests
- Apache 2.0 licensed (matching the original)

## Non-Goals

- Multi-tenant control plane
- Prescribing UI implementation
- Strong sandbox controls beyond what Codex provides
- A database for orchestrator state (matches SPEC: tracker-driven and filesystem-driven recovery)

## Architecture

### High-Level Layers

Following the SPEC's abstraction levels:

1. **Policy Layer** - `WORKFLOW.md` prompt body and rules (repo-defined, not implemented in code)
2. **Configuration Layer** - Parses front matter into typed runtime settings
3. **Coordination Layer** - Orchestrator with polling loop, retries, reconciliation
4. **Execution Layer** - Workspace manager and agent subprocess runner
5. **Integration Layer** - Linear adapter (GraphQL)
6. **Observability Layer** - Structured logging and optional HTTP dashboard

### Module Structure

The project is organized as a **multi-module Gradle project** with clear boundaries:

```
koncerto/
├── build.gradle.kts           (root, plugin management)
├── settings.gradle.kts        (module declarations)
├── gradle.properties
├── docs/
│   └── superpowers/
│       ├── specs/
│       └── plans/
├── koncerto-app/              (Spring Boot application entry point)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/
│           ├── KoncertoApplication.kt
│           ├── config/
│           └── Application.kt
├── koncerto-core/             (domain models, configuration, errors)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/core/
│           ├── model/        (Issue, WorkflowDefinition, etc.)
│           ├── config/       (ServiceConfig, typed getters)
│           └── error/        (Result wrapper, error types)
├── koncerto-workflow/         (workflow loading and template rendering)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/workflow/
│           ├── WorkflowLoader.kt
│           ├── FrontMatterParser.kt
│           └── PromptRenderer.kt (Liquid-like template engine)
├── koncerto-orchestrator/     (polling loop, scheduling, retries)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/orchestrator/
│           ├── Orchestrator.kt
│           ├── PollingLoop.kt
│           ├── Reconciliation.kt
│           ├── RetryQueue.kt
│           └── RuntimeState.kt
├── koncerto-workspace/        (filesystem lifecycle and hooks)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/workspace/
│           ├── WorkspaceManager.kt
│           ├── WorkspaceKey.kt
│           └── HookExecutor.kt
├── koncerto-agent/            (Codex app-server integration)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/agent/
│           ├── AgentRunner.kt
│           ├── CodexAppServerClient.kt
│           ├── AppServerProtocol.kt
│           └── EventStream.kt
├── koncerto-linear/           (Linear GraphQL adapter)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/linear/
│           ├── LinearClient.kt
│           ├── LinearGraphQLClient.kt
│           ├── IssueMapper.kt
│           └── queries/
├── koncerto-dashboard/        (optional HTTP observability)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/dashboard/
│           ├── DashboardController.kt
│           └── ApiV1Controller.kt
├── koncerto-logging/          (structured logging)
│   └── src/main/kotlin/
│       └── com/anomaly/koncerto/logging/
│           ├── StructuredLogger.kt
│           └── LogSinks.kt
└── koncerto-testing/          (test utilities, fakes)
    └── src/test/kotlin/
```

### Module Dependencies

```
koncerto-app
  ├── koncerto-core
  ├── koncerto-workflow      (depends on core)
  ├── koncerto-orchestrator  (depends on core, workflow)
  ├── koncerto-workspace     (depends on core, logging)
  ├── koncerto-agent         (depends on core, logging, workspace)
  ├── koncerto-linear        (depends on core)
  ├── koncerto-dashboard     (depends on core, orchestrator)
  └── koncerto-logging       (depends on core)
```

Dependency direction is strictly downward: `app -> features -> core/logging`. No circular dependencies.

## Key Components

### 1. Configuration Layer (`koncerto-core`, `koncerto-workflow`)

**Responsibilities:**
- Parse `WORKFLOW.md` YAML front matter and Markdown body
- Provide typed getters with defaults and `$VAR` environment resolution
- Validate configuration before startup and on reload
- Support hot-reload via filesystem watching

**Key types:**
- `WorkflowDefinition(config: Map<String, Any>, promptTemplate: String)`
- `ServiceConfig(pollIntervalMs, workspaceRoot, activeStates, terminalStates, ...)`
- `ConfigResolver` - resolves `$VAR` references and applies defaults

**Template engine:** Use a strict Liquid-compatible Kotlin template engine. Unknown variables/filters fail rendering (per SPEC 5.4).

### 2. Issue Tracker Client (`koncerto-linear`)

**Responsibilities:**
- GraphQL queries against Linear (`https://api.linear.app/graphql`)
- Three core operations:
  1. `fetchCandidateIssues()` - active states for dispatch
  2. `fetchIssuesByStates(stateNames)` - startup terminal cleanup
  3. `fetchIssueStatesByIds(issueIds)` - active-run reconciliation
- Normalize Linear payloads into the domain `Issue` model
- Pagination with cursor-based queries (page size 50)
- 30-second network timeout

**Technology:** Spring WebClient (reactive, non-blocking) for HTTP, kotlinx.serialization for JSON. GraphQL is sent as raw JSON strings.

**Authentication:** `Authorization: <api_key>` header. API key resolved from `LINEAR_API_KEY` env or `tracker.api_key` config.

**GraphQL schema strategy:** Store `.graphql` query files in `src/main/resources/graphql/`, load at startup. No code generation (Apollo-style) to keep dependencies minimal.

### 3. Orchestrator (`koncerto-orchestrator`)

**Responsibilities:**
- Owns the poll tick and in-memory state
- Reconciles active runs every tick
- Validates config before each dispatch
- Fetches candidate issues and dispatches eligible ones
- Manages retry queue with exponential backoff
- Tracks session metrics (tokens, runtime, rate limits)
- Single authoritative state owner (no concurrent mutation)

**Concurrency model:** Kotlin coroutines with a single `SupervisorJob` scope. A `Mutex` protects runtime state mutations. A `Channel` or `SharedFlow` propagates worker completion events back to the orchestrator.

**State machine:** Per-issue states: `Unclaimed`, `Claimed`, `Running`, `RetryQueued`, `Released`.

**Run attempt lifecycle phases:** `PreparingWorkspace` -> `BuildingPrompt` -> `LaunchingAgentProcess` -> `InitializingSession` -> `StreamingTurn` -> `Finishing` -> `Succeeded`/`Failed`/`TimedOut`/`Stalled`/`CanceledByReconciliation`.

**Polling loop:** `while (running) { reconcile(); preflight(); fetch(); dispatch(); delay(pollInterval) }` in a coroutine.

**Retry backoff:** `delay = min(10000 * 2^(attempt - 1), maxRetryBackoffMs)`. Continuation retries use fixed 1000ms delay.

### 4. Workspace Manager (`koncerto-workspace`)

**Responsibilities:**
- Map issue identifiers to sanitized workspace paths
- Create per-issue workspace directories
- Run lifecycle hooks: `after_create`, `before_run`, `after_run`, `before_remove`
- Enforce safety invariants (workspace path inside root, sanitized keys)

**Key types:**
- `WorkspaceKey` - sanitized identifier (replaces non `[A-Za-z0-9._-]` with `_`)
- `Workspace(path: Path, workspaceKey: String, createdNow: Boolean)`
- `HookExecutor` - executes shell scripts with timeout via `ProcessBuilder` and `bash -lc`

**Safety invariant checks** (per SPEC 9.5):
1. Agent subprocess `cwd == workspacePath`
2. `workspacePath.startsWith(workspaceRoot)` after normalization
3. Workspace key matches `[A-Za-z0-9._-]+`

### 5. Agent Runner (`koncerto-agent`)

**Responsibilities:**
- Build prompt from workflow template + issue
- Launch `codex app-server` subprocess in workspace
- Communicate via stdio (JSON-RPC 2.0 framing)
- Stream events back to orchestrator via callback/coroutine channel
- Handle multi-turn sessions (continuation on same thread)
- Enforce turn timeout, read timeout, stall timeout

**Subprocess model:**
- `ProcessBuilder("bash", "-lc", codexCommand).directory(workspacePath)`
- stdin: send JSON-RPC requests (newline-delimited)
- stdout: parse JSON-RPC responses and notifications
- stderr: separate diagnostic stream (logged but not parsed as protocol)

**Protocol:** JSON-RPC 2.0 with newline-delimited framing. Follow the targeted Codex app-server protocol schema (per SPEC 10.1, the Codex protocol is source of truth).

**Event types emitted to orchestrator:** `session_started`, `startup_failed`, `turn_completed`, `turn_failed`, `turn_cancelled`, `turn_ended_with_error`, `turn_input_required`, `approval_auto_approved`, `unsupported_tool_call`, `notification`, `other_message`, `malformed`.

**Token accounting:** Extract from `thread/tokenUsage/updated` payloads (absolute totals). Ignore `last_token_usage` (deltas). Track deltas relative to last reported totals.

**Optional client-side tool:** `linear_graphql` - exposes Linear GraphQL to the agent using the configured `LINEAR_API_KEY`. Advertise via the protocol's tool registration mechanism.

### 6. Logging (`koncerto-logging`)

**Responsibilities:**
- Structured key=value logging
- Multiple sinks (stderr by default, optional file)
- Required context fields: `issue_id`, `issue_identifier`, `session_id`
- Stable message format for log parsing

**Implementation:** Kotlin logging facade (`kotlin-logging`) with custom structured formatter. JSON output option for production.

### 7. HTTP Dashboard (`koncerto-dashboard`)

**Responsibilities (optional):**
- Human-readable dashboard at `/` (server-rendered HTML or client-side app)
- JSON API at `/api/v1/state`, `/api/v1/<issue_identifier>`, `/api/v1/refresh`
- Bound to loopback by default (`127.0.0.1`)

**Technology:** Spring WebFlux or Spring MVC. Recommend Thymeleaf for server-rendered HTML to keep dependencies minimal.

**Endpoints:**
- `GET /` - dashboard
- `GET /api/v1/state` - runtime snapshot (running, retrying, codex_totals, rate_limits)
- `GET /api/v1/{issue_identifier}` - per-issue detail
- `POST /api/v1/refresh` - force workflow reload

## Configuration & Workflow

### `WORKFLOW.md` Support

- Read from explicit path or default `./WORKFLOW.md`
- Parse YAML front matter + Markdown body
- Strict template engine (unknown variables/filters fail)
- Hot-reload on file change (file watcher)
- Invalid reloads keep last-known-good config (per SPEC 6.2)

### `application.yml` (Spring)

Minimal Spring config - delegates to `WORKFLOW.md` for Symphony-specific config. Spring config only controls:
- HTTP server port (when dashboard enabled)
- Logging level
- Spring-specific settings

## Error Handling

- **Result wrapper:** Custom `Result<T, E>` with `onSuccess`/`onFailure` extension functions
- **Error categories:**
  - `WorkflowError` - parse, validation, render errors
  - `TrackerError` - API/GraphQL errors
  - `WorkspaceError` - path safety, hook execution errors
  - `AgentError` - process, protocol, timeout errors
- **Failure semantics:**
  - Workflow errors block dispatch until fixed
  - Tracker errors: log and skip tick
  - Agent errors: fail attempt, orchestrator schedules retry

## Testing Strategy

- **Unit tests:** JUnit 5 + AssertK + kotlinx-coroutines-test
- **Coroutines tests:** `runTest` with `TestDispatcher`
- **Fakes:** In-memory fakes for `LinearClient`, `WorkspaceManager`, `AgentRunner`
- **Integration tests:** Spring Boot tests with Testcontainers (Linear mocked at HTTP layer via WireMock)
- **Codex protocol tests:** Decode/encode round-trip with sample JSON-RPC messages
- **End-to-end test:** Real `codex app-server` + real Linear (gated by env var, like Elixir `make e2e`)

Target: 80%+ coverage on core modules. 100% on safety invariants.

## Build & Tooling

- **Build:** Gradle 8.x with Kotlin DSL
- **Language:** Kotlin 2.0+ (latest stable)
- **JVM:** Java 21 (virtual threads available if needed; coroutines are primary)
- **Framework:** Spring Boot 3.2+
- **HTTP client:** Spring WebClient (reactive, non-blocking)
- **JSON:** kotlinx.serialization
- **Logging:** kotlin-logging + logback
- **YAML:** snakeyaml
- **Template engine:** Liqp (Kotlin Liquid port) or custom minimal Liquid-compatible engine

## Deployment

- **Build:** `./gradlew bootJar` produces executable JAR
- **Run:** `java -jar koncerto-app/build/libs/koncerto-app-*.jar /path/to/WORKFLOW.md`
- **CLI args:**
  - Positional: path to `WORKFLOW.md`
  - `--logs-root <path>`: log directory (default `./log`)
  - `--port <port>`: enable HTTP dashboard
- **Docker:** Multi-stage Dockerfile (planned, not in v1)

## Out of Scope (v1)

- Multi-tenant control plane
- Database-backed orchestrator state
- Strong sandbox controls (rely on Codex)
- Distributed deployment
- Custom UI beyond a simple dashboard

## Open Questions

None at this time. All design decisions confirmed with the user.

## Success Criteria

- [ ] All SPEC components implemented
- [ ] `WORKFLOW.md` loading, parsing, and hot-reload working
- [ ] Linear polling, dispatch, and reconciliation working
- [ ] Per-issue workspace creation with hook execution
- [ ] Codex app-server subprocess integration via stdio
- [ ] Multi-turn agent sessions with continuation
- [ ] Retry queue with exponential backoff
- [ ] Structured logging with required context fields
- [ ] Optional HTTP dashboard with JSON API
- [ ] Unit and integration tests with good coverage
- [ ] README with usage instructions
- [ ] Build produces runnable JAR

## References

- [Symphony SPEC v1](https://github.com/openai/symphony/blob/main/SPEC.md)
- [Symphony Elixir implementation](https://github.com/openai/symphony/tree/main/elixir)
- [Codex app-server docs](https://developers.openai.com/codex/app-server/)
- [Linear GraphQL API](https://developers.linear.app/docs/graphql/working-with-the-graphql-api)
