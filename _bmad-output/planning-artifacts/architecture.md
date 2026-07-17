# Architecture Document: Koncerto

**Version:** 2.0  
**Date:** 2026-06-25  
**Architect:** Fred the Architect  
**Status:** Updated (v2.0: demo recording, auto-deploy, metrics, notifications, auto-review)  

---

## 1. System Overview

Koncerto is a Kotlin/Spring Boot application that orchestrates AI coding agents by connecting project trackers (Linear) with agent runtimes (Codex, opencode). It follows a modular, layered architecture with strict dependency direction.

```
┌─────────────────────────────────────────────────────────────┐
│                      koncerto-app                           │
│  (Spring Boot entry point, bean wiring, CLI runner)         │
└──────────────────────────┬──────────────────────────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│ Orchestrator │  │  Dashboard   │  │  CLI Runner  │
│  (poll loop) │  │   (REST)     │  │   (args)     │
└──────┬───────┘  └──────────────┘  └──────────────┘
       │
       ├──────────────┬──────────────┐
       ▼              ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────────┐
│  Linear  │  │  Agent   │  │  Workspace   │
│  Client  │  │  Runner  │  │   Manager    │
└──────────┘  └──────────┘  └──────────────┘
       │              │              │
       ▼              ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────────┐
│ GraphQL  │  │  Codex   │  │  Shell Hook  │
│  Client  │  │  Subproc │  │   Executor   │
└──────────┘  └──────────┘  └──────────────┘
```

## 2. Technology Stack

| Layer | Technology | Version | Rationale |
|-------|------------|---------|-----------|
| Language | Kotlin | 2.0.21 | Null safety, coroutines, JVM ecosystem |
| Framework | Spring Boot | 3.3.5 | Mature, dependency injection, WebFlux |
| Build | Gradle | 8.10 | Kotlin DSL, dependency catalogs |
| JDK | OpenJDK | 21+ | LTS, virtual threads (future) |
| Concurrency | Kotlin Coroutines | 1.9.0 | Structured concurrency, Flow |
| HTTP Client | Spring WebClient | (via Spring Boot) | Non-blocking, reactive |
| Serialization | kotlinx.serialization | 1.7.3 | Compile-time, no reflection |
| Templating | liqp | (Liquid) | Simple, well-known syntax |
| Testing | JUnit 5, AssertK, MockK | — | Modern, expressive |
| Coverage | JaCoCo | — | Industry standard |
| Video Recording | Playwright, ffmpeg | 1.49.0 | Cross-browser web app demo capture |
| Storage | SQLite via JDBC | — | Local metrics persistence |
| Cloud Storage | R2/S3-compatible (SigV4) | — | Demo video artifact upload |
| Container Runtime | Docker (docker-compose) | — | Target project deploy for demos |
| HTTP Client | OkHttp | — | Agent process I/O, JSON-RPC framing |

## 3. Module Architecture

### 3.1 Module Dependency Graph

```
koncerto-app  (Spring Boot entry point, bean wiring, CLI runner)
   │
   ├── koncerto-dashboard    (REST API, HTML dashboard, admin)
   ├── koncerto-orchestrator (Poll loop, dispatch, retry, auto-review)
   ├── koncerto-demo         (Playwright recording, R2 upload, ffmpeg)
   ├── koncerto-deploy       (Target project Docker build/run/cleanup)
   ├── koncerto-metrics      (SQLite metrics, Prometheus exporter)
   ├── koncerto-notifications(Webhook, Telegram, Email)
   ├── koncerto-linear       (Linear GraphQL client)
   ├── koncerto-agent        (Agent runtimes: Codex, opencode, Claude)
   ├── koncerto-workspace    (Git, workspace isolation, hooks)
   ├── koncerto-workflow     (YAML frontmatter, Liquid templates)
   ├── koncerto-logging      (Structured logging)
   └── koncerto-core         (Result, Issue, Config, RateLimiter, etc.)
```

### 3.2 Module Responsibilities

| Module | Responsibility | Key Classes | Dependencies |
|--------|---------------|-------------|--------------|
| koncerto-core | Shared types, config, rate limiting, circuit breakers, events, tenant | Result, ServiceConfig, ProjectConfig, TokenBucketRateLimiter, EventBus, TenantResolver, CircuitBreaker, ErrorTracker | None |
| koncerto-logging | Structured logging | StructuredLogger, StderrSink, FileSink, AuditLogger | core |
| koncerto-workflow | YAML parsing, template rendering | FrontMatterParser, PromptRenderer, WorkflowCache | core |
| koncerto-workspace | Workspace isolation, git operations, hooks | WorkspaceManager, GitWorkflow, HookExecutor, WorkspaceKey | core, logging |
| koncerto-linear | Linear GraphQL integration | LinearGraphQLClient, RateLimitedLinearClient, IssueMapper, LinearError | core |
| koncerto-agent | Agent abstraction & runtimes (Codex, opencode, Claude) | AgentRuntime, AgentRuntimeFactory, CodexRuntime, OpencodeRuntime, ClaudeReviewRuntime, AgentHealthChecker | core, logging, workflow, workspace |
| koncerto-orchestrator | Poll loop, dispatch, retry, auto-review, dependency graph, follow-ups, subtasking | Orchestrator, DispatchService, RuntimeState, AutoReviewOrchestrator, DependencyGraph, FollowUpRenderer, CrossProjectChainer, AgentMessageStore, SubtaskOrchestrator | core, logging, workflow, workspace, agent, linear, metrics, notifications |
| koncerto-dashboard | REST API, HTML dashboard, admin API | ApiV1Controller, DashboardController, AdminController, TunnelController | core, orchestrator, metrics, workflow |
| koncerto-metrics | SQLite metrics, Prometheus binding | MetricsRepository, SqliteMetricsRepository, PrometheusMetricsBinder, IssueMetrics | core |
| koncerto-notifications | Webhook, Telegram, SMTP notifications | Notifier, CompositeNotifier, WebhookNotifier, TelegramNotifier, SmtpEmailNotifier, LoggingNotifier | core, logging |
| koncerto-demo | Playwright demo recording, R2 upload, ffmpeg | DemoRecordingService, PlaywrightRecorder, R2DemoStorage, DemoEventListener, DemoRecorder, FfmpegRecorder | core, logging, linear |
| koncerto-deploy | Target project Docker build/run/cleanup | TargetProjectDeployer, ContainerLifecycleManager, DockerConfigDetector, FrameworkDetector, DockerfileGenerator | core, logging |
| koncerto-app | Application entry point, bean wiring | KoncertoApplication, Beans, ConfigService, CliRunner, OrchestratorHealthIndicator | All modules |

### 3.3 Dependency Rules

1. **Strict Direction:** Dependencies flow downward only (core → logging → workflow → ...)
2. **No Circular Dependencies:** Enforced by Gradle module system
3. **Interface Segregation:** Modules depend on interfaces, not implementations
4. **Test Isolation:** Each module has its own test suite

## 4. Data Flow

### 4.1 Issue Lifecycle

```
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ Linear  │────►│Orchestr. │────►│  Agent   │────►│ Linear   │
│ (Todo)  │     │ (poll)   │     │ (Codex/  │     │ (Done)   │
└─────────┘     └────┬─────┘     │ opencode)│     └──────────┘
                     │           └────┬─────┘           
                     ▼                ▼                
               ┌──────────┐    ┌──────────┐            
               │ Workspace│    │  Events  │            
               │ (create) │    │ (stream) │            
               └──────────┘    └──────────┘            
```

### 4.2 Dispatch Flow

1. **Poll** → Orchestrator.tick() calls LinearClient.fetchCandidateIssues()
2. **Filter** → Remove running/claimed, check labels, check blockers
3. **Sort** → Priority ascending, then creation date
4. **Dispatch** → For each eligible issue (respecting concurrency limits):
   - Create workspace via WorkspaceManager
   - Execute after_create hook
   - Render prompt via WorkflowCache.current()
   - Resolve prompt file path → read prompt content (resolvePrompt)
   - Select agent runtime via AgentRuntimeFactory (Codex, opencode, or Claude)
   - Start agent via AgentRuntime.run()
5. **Monitor** → Agent emits events, orchestrator tracks state
6. **Complete** → Handle completion via stage config (onCompleteState):
   - If review_passed trigger → run AutoReviewOrchestrator
   - If auto-review passes → optionally deploy target project (koncerto-deploy)
   - If demo recording enabled → record via Playwright, upload to R2
   - Post PR comment with review + demo link
   - Clean up demo containers

### 4.3 Retry Flow

```
Agent fails
    │
    ▼
scheduleRetry()
    │
    ▼
┌─────────────────────┐
│ Calculate backoff:  │
│ 10s * 2^(attempt-1) │
│ capped at max       │
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ Store RetryEntry:   │
│ - issueId           │
│ - attempt           │
│ - dueAtMs           │
│ - error             │
└─────────────────────┘
    │
    ▼
On next tick:
    │
    ▼
Check retryAttempts
    │
    ▼
If dueAtMs <= now:
    │
    ▼
Re-dispatch
```

## 5. Concurrency Model

### 5.1 Coroutine Scopes

| Scope | Usage | Lifecycle |
|-------|-------|-----------|
| Orchestrator.scope | Main poll loop + agent dispatch | Application lifetime |
| runBlocking | CLI runner, tests | Synchronous entry points |
| Dispatchers.IO | HTTP calls, file I/O | Per-operation |

### 5.2 Thread Safety

| Component | Strategy | Rationale |
|-----------|----------|-----------|
| RuntimeState.running | mutableMapOf() | Single-writer via coroutine |
| RuntimeState.claimed | mutableSetOf() | Single-writer |
| RuntimeState.retryAttempts | mutableMapOf() | Single-writer |
| LinearGraphQLClient.execute | withContext(Dispatchers.IO) | Avoid reactor deadlock |

### 5.3 Concurrency Limits

```kotlin
// Per-global limit
state.availableSlots() > 0

// Per-state limit
val perStateLimit = config.maxConcurrentAgentsByState[issue.normalizedState]
val currentForState = state.running.values.count { 
    it.issue.normalizedState == issue.normalizedState 
}
currentForState < (perStateLimit ?: state.maxConcurrentAgents)
```

## 6. Error Handling

### 6.1 Result Type

```kotlin
sealed class Result<out T, out E> {
    data class Success<T>(val value: T) : Result<T, Nothing>()
    data class Failure<E>(val error: E) : Result<Nothing, E>()
    
    fun <R> map(transform: (T) -> R): Result<R, E>
    fun onSuccess(action: (T) -> Unit): Result<T, E>
    fun onFailure(action: (E) -> Unit): Result<T, E>
    fun getOrNull(): T?
    fun exceptionOrNull(): Throwable?
}
```

### 6.2 Error Categories

| Module | Error Type | Examples |
|--------|------------|----------|
| Core | IllegalStateException | Config validation failures |
| Linear | LinearError | MissingApiKey, Status, GraphQlErrors |
| Agent | AgentError | SubprocessFailure, TurnTimeout |
| Agent | CodexError | CodexProtocolError, CodexSpawnError |
| Agent | OpencodeError | OpencodeProtocolError, OpencodeSpawnError |
| Workspace | WorkspaceError | HookExecutionFailed |

### 6.3 Recovery Strategies

| Error | Strategy |
|-------|----------|
| Linear API failure | Skip fetch, retry on next tick |
| Agent crash | Schedule retry with backoff |
| Hook failure | Log warning, continue |
| Workspace creation failure | Skip issue, log error |

## 7. Configuration

### 7.1 Configuration Hierarchy

```
WORKFLOW.md (primary)
    │
    ▼
ServiceConfig.fromMapOrError()
    │
    ▼
┌─────────────────────┐
│ Environment Variables│
│ (LINEAR_API_KEY)     │
└─────────────────────┘
    │
    ▼
┌─────────────────────┐
│ Path Expansion      │
│ (~, $HOME)          │
└─────────────────────┘
```

### 7.2 Validation Rules

| Field | Rule | Error |
|-------|------|-------|
| tracker.api_key | Required, not blank | missing_tracker_api_key |
| tracker.project_slug | Required, not blank | missing_tracker_project_slug |
| agent.kind | codex or opencode | invalid_agent_kind |
| agent.max_concurrent_agents | >= 1 | — |
| agent.max_turns | >= 1 | — |
| workspace.root | Valid path | — |
| codex.command | Valid command | — |
| opencode.command | Valid command | — |

## 8. Testing Strategy

### 8.1 Test Pyramid

```
         ┌────────────┐
         │   E2E      │ ← (future)
         │  (10%)     │
         └─────┬──────┘
               │
         ┌─────┴──────┐
         │Integration │ ← (30%)
         │  Tests     │
         └─────┬──────┘
               │
    ┌──────────┴──────────┐
    │    Unit Tests       │ ← (60%)
    │  (per module)       │
    └─────────────────────┘
```

### 8.2 Test Patterns

| Pattern | Usage | Example |
|---------|-------|---------|
| Fake implementations | Mock external dependencies | FakeLinearClient, FakeAgentRunner |
| In-memory state | Test state management | RuntimeState in tests |
| Temp directories | Test file operations | @TempDir for workspace tests |
| Coroutine test | Test async code | runBlocking, TestScope |

### 8.3 Coverage Targets

| Module | Target | Current |
|--------|--------|---------|
| koncerto-core | 90% | 83% |
| koncerto-logging | 95% | 92% |
| koncerto-workflow | 90% | 87% |
| koncerto-workspace | 95% | 95% |
| koncerto-linear | 85% | 84% |
| koncerto-agent | 85% | 95% |
| koncerto-orchestrator | 80% | 84% |
| koncerto-dashboard | 95% | 95% |

## 9. Deployment

### 9.1 Build Commands

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test

# Generate coverage report
./gradlew jacocoTestReport

# Create executable JAR
./gradlew :koncerto-app:bootJar
```

### 9.2 Runtime Requirements

| Requirement | Minimum |
|-------------|---------|
| JDK | 21+ |
| Memory | 512 MB |
| Disk | 1 GB (for workspaces) |
| Network | Linear API access, Codex binary, opencode binary |

### 9.3 Docker (Future)

```dockerfile
FROM eclipse-temurin:21-jre-alpine
COPY koncerto-app/build/libs/koncerto-app-*.jar /app/koncerto.jar
ENTRYPOINT ["java", "-jar", "/app/koncerto.jar"]
```

## 10. Security Considerations

| Concern | Mitigation |
|---------|------------|
| API keys | Read from environment, not config files |
| Workspace isolation | Separate directories per issue |
| Hook execution | Timeout limits, no shell injection |
| HTTP endpoints | No auth (internal use) |

## 11. Performance Characteristics

| Metric | Value |
|--------|-------|
| Cold start | < 5 seconds |
| Poll cycle | 30 seconds (configurable) |
| Dispatch latency | < 1 second |
| Memory per workspace | ~50 MB |
| Max concurrent | 10 agents (configurable) |

## 12. Technical Decisions

| Decision | Options Considered | Choice | Rationale |
|----------|-------------------|--------|-----------|
| Language | Kotlin vs Java | Kotlin | Null safety, coroutines, modern syntax |
| Framework | Spring Boot vs Ktor | Spring Boot | Mature ecosystem, dependency injection |
| Build | Gradle vs Maven | Gradle | Kotlin DSL, better Kotlin support |
| HTTP Client | WebClient vs RestTemplate | WebClient | Non-blocking, reactive |
| Serialization | Gson vs kotlinx.serialization | kotlinx.serialization | Compile-time, no reflection |
| Templating | FreeMarker vs liqp | liqp | Simple, Liquid syntax |
| Testing | JUnit4 vs JUnit5 | JUnit5 | Modern, parameterized tests |
| Agent Runtime | Single (Codex) vs Multiple | Multiple (Codex, opencode) | Flexibility, user choice, future-proofing |
| Agent Abstraction | Direct implementation vs Interface | AgentRuntime interface | Extensibility, testability, clean architecture |

## 13. Enhanced Dispatch Architecture (v1.1)

### 13.1 Dependency Graph & Parallel Dispatch

The orchestrator builds a dependency DAG from `blockedBy` relations and dispatches from the "frontier" — issues whose blockers are all resolved.

```
DependencyGraph
├── nodes: Map<String, Issue>       // candidate issues by ID
├── edges: Map<String, Set<String>> // issueId → blocker IDs (in candidate set, not terminal)
└── frontier: List<Issue>           // sorted by priority, then identifier

Frontier Rules:
- Blocker absent from candidate set → resolved (external/completed)
- Blocker id = null → resolved (unlinked reference)
- Blocker in terminal state → resolved
- All other blockers → unresolved
```

New file: `DependencyGraph.kt` in `koncerto-orchestrator`.

### 13.2 Blocker State Tracking

`reconcile()` monitors blocker states at each poll cycle:

```
1. Fetch states for all running issues (existing)
2. Clean up completed/non-active issues (existing)
3. Scan remaining running issues' blockedBy entries
4. If all blockers in terminal state → log "unblocked"
5. Next poll → frontier recomputed → unblocked issue becomes available
```

No new API calls needed — reuses existing `fetchIssueStatesByIds()` batch query.

### 13.3 Agent Specialization Routing

Configurable routing rules evaluated before agent resolution:

```
resolveAgent() priority chain:
  1. Stage config agent provider (highest)
  2. Label agent: prefix override
  3. → Routing rules (new, step 0) ←
  4. Default kind/command/model (lowest)
```

Rules sorted by `priority` descending. First match wins. Supported conditions: `ifLabel`, `ifLabelPrefix`, `ifState`, `ifPriority`, `ifPriorityMax`.

New data class: `RoutingRule` in `koncerto-core/config/ProjectConfig.kt`.

### 13.4 Workflow Chaining

When an issue transitions to its `onCompleteState`, the orchestrator can create a follow-up issue:

```
transitionOnComplete()
  ├── Update issue state to onCompleteState
  ├── If stageConfig.followUp != null:
  │   ├── Render titleTemplate (FollowUpRenderer)
  │   ├── linear.createIssue(...)
  │   ├── linear.createLink(sourceId, newId, linkType)
  │   └── Log "follow_up_created"
  └── Done
```

New files:
- `FollowUpConfig` in `koncerto-core/config/ProjectConfig.kt`
- `FollowUpRenderer` in `koncerto-orchestrator`

New LinearClient methods:
- `createIssue(projectSlug, title, state, description, labels): Issue?`
- `createLink(sourceId, targetId, type): Boolean`

---

## 14. Auto-Review Architecture

### 14.1 Overview

After an agent completes implementation and creates a PR, the `AutoReviewOrchestrator` runs Claude Code against the PR diff to validate code quality before state transition.

Since Epics 18–23 this is a staged pipeline rather than a single opaque model call. The
governing principle: **everything that can be deterministic is Kotlin, not prompt** — eligibility,
routing, gating, verdict derivation, and state transitions are code; the model only reads code
and emits findings. See `architecture-review-quality.md` for the full design and decision records.

```
Issue enters "In Review"
  ├─ 1. ReviewEligibility     (skip artifact/generated/docs-only diffs — no model call)
  ├─ 2. RiskRouter            (low | standard | critical tier)
  ├─ 3. ReviewContextBuilder  (bounded context pack: intent, invariants, neighbors)
  ├─ 4. ClaudeReviewRuntime   (JSON envelope → human text + structured findings + usage)
  │      └─ critical tier: SpecialistReviewCoordinator fan-out, merged + deduped
  ├─ 5. PublicationGate       (confidence thresholds; dropped findings persisted, not posted)
  ├─ 6. Publish               (PR comment w/ koncerto-finding markers)
  ├─ 7. ReviewTelemetryRecorder (review_runs + review_findings)
  └─ 8. Transition            (advisory → always complete; blocking → Needs Fix loop)
         └─ FindingOutcomeTracker: fix-agent report + re-review corroboration
```

### 14.2 Flow

```
agent_completes → PR_created → AutoReviewOrchestrator.review()
    ├── Read .review-output-detailed (backup of previous review)
    ├── Eligibility pre-check (skip trivial diffs → record skipped run, transition)
    ├── Run ClaudeReviewRuntime: claude --print --output-format json with prompts/review.md
    │   ├── Filter stderr config errors from output
    │   ├── ReviewOutputParser: unwrap envelope, extract `review-findings` block
    │   │   └── parse failure → fallback to legacy verdict string (parse_status=fallback)
    │   └── Write .review-status, .review-output (human text), .review-findings.json
    ├── Parse verdict: any critical finding → fail (legacy ❌ FAIL string as fallback)
    │   ├── FAIL → post comment, re-dispatch (up to max_review_attempts) [blocking mode]
    │   ├── FAIL → publish + record, transition anyway            [advisory mode]
    │   └── PASS → proceed to demo/deploy
    ├── If target_project_deploy.enabled:
    │   ├── TargetProjectDeployer.deploy()
    │   │   ├── Detect Docker config (docker-compose.yml / Dockerfile)
    │   │   ├── Detect framework (Spring Boot, Node, Python, Go)
    │   │   ├── Build Docker image
    │   │   ├── Start container on free port (32768-33000)
    │   │   └── Health check
    │   └── If no Docker config → generate Dockerfile.koncerto
    ├── If demo_recording.enabled:
    │   ├── DemoRecordingService.record(targetUrl)
    │   │   ├── Playwright: launch Chromium, navigate, capture 120s
    │   │   ├── ffmpeg: convert raw capture to VP9 video
    │   │   └── R2DemoStorage.upload(): SigV4 PUT to R2/S3
    │   └── Return recording URL
    ├── postDetailedReviewAsPrComment()
    │   ├── gh pr comment <PR#> --repo <owner/repo> --body-file
    │   └── Appends 🎥 demo URL if recording was made
    └── CleanupDemoDeploy(): docker rm/rmi, compose down
         → State transition to onCompleteState
```

### 14.3 Key Components

| Component | Module | Responsibility |
|-----------|--------|---------------|
| AutoReviewOrchestrator | koncerto-orchestrator | Orchestrates review → deploy → demo → comment lifecycle |
| ClaudeReviewRuntime | koncerto-agent | Spawns claude --print, parses findings + usage, writes handoff files |
| ReviewOutputParser / PublicationGate / RiskRouter / ReviewEligibility / Glob | koncerto-core (`core.review`) | Pure review decisions + parsing (no IO) |
| ReviewDiffInspector / ReviewContextBuilder / ReviewTelemetryRecorder / FindingOutcomeTracker / SpecialistReviewCoordinator / ReviewCommentRenderer | koncerto-orchestrator (`orchestrator.review`) | IO-bound pipeline stages |
| ReviewMetricsRepository | koncerto-metrics | review_runs + review_findings persistence, baseline aggregates |
| ReviewController | koncerto-dashboard | `/api/v1/review/*` — runs, findings, human labels, baseline |
| TargetProjectDeployer | koncerto-deploy | Docker build/run/health/cleanup for target project |
| DemoRecordingService | koncerto-demo | Coordinates Playwright + ffmpeg + R2 upload |
| PlaywrightRecorder | koncerto-demo | Embedded Node.js Playwright script via Xvfb |
| R2DemoStorage | koncerto-demo | SigV4 upload to R2/S3-compatible storage |

## 15. Demo Recording Architecture

### 15.1 Recorder Backends

| Recorder | Platform | Technology |
|----------|----------|------------|
| PlaywrightRecorder | Web | Chromium + Playwright + ffmpeg via Xvfb |
| AsciinemaRecorder | Terminal | Asciinema CLI capture |
| AdbRecorder | Android | ADB screenrecord |
| XcrunRecorder | iOS | XCUITest / xcrun |

### 15.2 Storage Layer

`R2DemoStorage` implements `DemoStorage` interface using SigV4 authentication:

```
PUT /{bucket}/{path}
  Headers: Content-MD5, Content-Type, Host, x-amz-acl
  Auth: AWS4-HMAC-SHA256 (canonical request sorted alphabetically)
→ Returns presigned URL with 10yr TTL
```

## 16. Notifications Architecture

### 16.1 Notifier Chain

```
Notifier (interface)
  └── CompositeNotifier
      ├── LoggingNotifier (always active)
      ├── WebhookNotifier (configurable URL)
      ├── TelegramNotifier (configurable chat ID + bot token)
      └── SmtpEmailNotifier (configurable SMTP server + recipients)
```

Each notifier is independently configured per project in WORKFLOW.md. The `CompositeNotifier` fans out `NotificationEvent` to all active channels. `LimitCooldownTracker` prevents duplicate notifications within a configurable window.

## 17. Metrics & Monitoring Architecture

### 17.1 Metrics Pipeline

```
Issue event → MetricsRepository (interface)
                   │
          ┌────────┴────────┐
          ▼                 ▼
 SqliteMetricsRepository   PrometheusMetricsBinder
 (SQLite via JDBC)         (Micrometer MeterRegistry)
                              │
                              ▼
                     /actuator/prometheus
                     (scraped by Prometheus)
```

### 17.2 Tracked Metrics

| Metric | Type | Description |
|--------|------|-------------|
| koncerto_dispatch_total | Counter | Total dispatch attempts |
| koncerto_completion_total | Counter | Successful completions |
| koncerto_retry_total | Counter | Retry attempts |
| koncerto_running_agents | Gauge | Currently running agents |
| koncerto_blocked_issues | Gauge | Issues in blocked state |
| JVM metrics (memory, threads, GC) | Various | Standard Micrometer JVM metrics |

## 18. Deployment Architecture

### 18.1 Docker (Current)

The project uses a multi-stage `Dockerfile`:
- **Build stage**: Gradle with cache mounts for dependencies
- **Runtime stage**: `eclipse-temurin:21-jre-alpine` with non-root user

`docker-compose.yml` defines:
- `koncerto` service: application container on port 17348
- `ngrok` service: tunnel for dashboard accessibility

### 18.2 Agent Runtime Image

`Dockerfile.agent` provides the full agent execution environment:
- Python 3, Node.js 20, Go, JDK 21
- Playwright + Chromium for demo recording
- ffmpeg for video encoding
- docker-compose for target project deploy
- GitHub CLI (`gh`) for PR operations

## 19. Core Subsystems Architecture

### 19.1 Rate Limiting

Token bucket algorithm (`TokenBucketRateLimiter`) with per-provider configuration:
- Per-minute and per-hour limits
- Token refill at 1-second granularity
- `RateLimitRegistry` singleton manages named provider limiters
- `RateLimitMonitor` with threshold-based alerting

### 19.2 Circuit Breakers

Two levels of circuit breaking:
- **Per-agent-key**: `AgentCircuitBreaker` prevents launching agents on failing providers
- **Provider-level**: `ProviderCircuitBreaker` with CLOSED/OPEN/HALF_OPEN states

### 19.3 Event Bus

`EventBus` uses Kotlin `MutableSharedFlow` for pub/sub:
- `AgentLifecycleEvent` sealed class (started, completed, failed)
- Subscribers consume via `SharedFlow`
- Used for in-process event propagation

### 19.4 Audit & Error Tracking

- `AuditLogger` with `AuditEvent` sealed class for structured audit trail
- `ErrorTracker` with `DefaultErrorTracker` for aggregating error patterns
- `PatternErrorClassifier` categorizes errors by pattern matching

## 20. Future Considerations

| Area | Options | Trade-offs |
|------|---------|------------|
| Multi-project | Support multiple Linear projects | Complexity vs flexibility |
| Agent types | Support additional agent runtimes | Implementation effort vs value |
| Persistence | Database for audit trail | Storage vs observability |
| Auth | API key or OAuth for dashboard | Security vs simplicity |
| Monitoring | Prometheus metrics | Integration effort vs observability |
| K8s | Helm chart for Kubernetes deployment | Covered by Epic 15 (not implemented) |
