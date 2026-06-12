# Technical Decisions & Lessons Learned

## 1. Architecture Decisions

### Module Dependency Rules

Modules follow a strict dependency hierarchy. The `:koncerto-core` module sits at the bottom and is depended on by everything. No module may depend on `:koncerto-app`.

```
:koncerto-app  (Spring Boot assembly — depends on every feature module)
  |
  +-- :koncerto-dashboard       (Web controllers — depends on core, metrics, orchestrator)
  +-- :koncerto-orchestrator    (Agent orchestration — depends on core, logging, workflow, workspace, agent, linear, metrics, notifications)
  +-- :koncerto-metrics         (Prometheus/SQLite metrics — depends on core)
  +-- :koncerto-notifications   (Webhook/Telegram/email — depends on core, logging, agent)
  +-- :koncerto-linear          (Linear API client — depends on core)
  +-- :koncerto-agent           (Agent runner — depends on core)
  +-- :koncerto-workspace       (Git/workspace mgmt — depends on core)
  +-- :koncerto-workflow        (Workflow loading/caching — depends on core)
  +-- :koncerto-logging         (Structured logging — depends on core)
  |
  :koncerto-core                (No dependencies on other project modules)
```

Key rules:
- `:koncerto-core` depends on nothing except Kotlin stdlib, kotlinx-coroutines, and kotlinx-serialization.
- `:koncerto-app` depends on every other module but no module depends on `:koncerto-app`.
- `:koncerto-dashboard` may depend on `:koncerto-core`, `:koncerto-metrics`, `:koncerto-orchestrator`, `:koncerto-workflow` — but never on `:koncerto-app`.
- For test scope, modules may depend on additional project modules (e.g., `:koncerto-dashboard` tests depend on `:koncerto-agent`, `:koncerto-linear`, `:koncerto-logging`, `:koncerto-workspace`, `:koncerto-notifications`).

### Circular Dependency Resolution

**Problem:** `:koncerto-dashboard` originally depended on `:koncerto-app` for `ConfigService`, creating a circular dependency since `:koncerto-app` already depended on `:koncerto-dashboard`.

**Solution:** `ConfigService` was moved into `:koncerto-app` (`koncerto-app/src/main/kotlin/.../app/ConfigService.kt`), where it naturally belongs as the application assembly layer. The dashboard now only depends on `ServiceConfig` from `:koncerto-core` for config access. The dashboard also interacts with `Orchestrator` (from `:koncerto-orchestrator`) and `MetricsRepository` (from `:koncerto-metrics`) — both valid upward dependencies.

### Package Organization

The `:koncerto-core` module is organized by feature layer, with sub-packages for each concern:

```
koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/
  agent/           — AgentCircuitBreaker, ProviderFallback
  config/          — ServiceConfig, ProjectConfig, WorkplanConfig, FollowUpConfig, etc.
  circuitbreaker/  — CircuitBreakerRegistry, ProviderCircuitBreaker with CircuitBreakerConfig
  errors/          — ErrorTracker interface + DefaultErrorTracker
  events/          — EventBus (SharedFlow), AgentLifecycleEvent sealed class
  model/           — Issue, UserRef domain models
  quota/           — QuotaConfig, QuotaEnforcer
  ratelimit/       — RateLimitProvider, RateLimitMonitor, RateLimitRegistry
  result/          — Result<T, E> sealed class, EmptyResult, runCatchingResult
  retry/           — RetryStrategy with exponential backoff + jitter
  tenant/          — TenantResolver, TenantContext, ConfigTenantResolver
  tracker/         — TrackerClient interface
```

Other modules follow this pattern:
- `:koncerto-linear` — `LinearClient`, `LinearGraphQLClient`, `RateLimitedLinearClient`, `LinearError`, `IssueMapper`
- `:koncerto-orchestrator` — `Orchestrator`, `DispatchService`, `RuntimeState`, `RetryExecutor`, `DependencyGraph`, `AgentMessageStore`, `CrossProjectChainer`, `SubtaskFrontier`, `SubtaskOrchestrator`, `WorkplanParser`, `FollowUpRenderer`
- `:koncerto-dashboard` — `DashboardController`, `ApiV1Controller`, `admin/AdminController`, `admin/ProjectRegistry`
- `:koncerto-notifications` — `Notifier` / `CompositeNotifier`, `NotificationEvent`, `channel/WebhookNotifier`, `channel/TelegramNotifier`, `channel/SmtpEmailNotifier`, `channel/LoggingNotifier`
- `:koncerto-app` — `KoncertoApplication`, `Beans` (Koin-style Spring @Configuration), `ConfigService`, `CliRunner`, `OrchestratorHealthIndicator`

## 2. Pattern Decisions

### MVI Pattern

The project does not use Compose UI/Android MVI. The "MVI-like" pattern referenced in Epic 13 refers to the **dispatch loop in `DispatchService`** (`koncerto-orchestrator/src/main/kotlin/.../orchestrator/DispatchService.kt`, 641 lines). The service:
- Accepts **actions** (process issue, retry, follow-up)
- Reads **state** (`RuntimeState` — running/retrying/blocked maps)
- Emits **side effects** (notifications, agent launches, Linear API calls)

There is no formal State/Action/Event ViewModel pattern as in Android MVI. The dispatch loop polls `RuntimeState`, evaluates routing rules, and launches agents accordingly. The `Orchestrator` class (`230 lines`) manages the top-level polling loop and projection per project.

### Result Wrapper

`koncerto-core/src/main/kotlin/.../core/result/Result.kt` defines a generic sealed class:

```kotlin
sealed class Result<out T, out E : Throwable> {
    data class Success<T>(val value: T) : Result<T, Nothing>()
    data class Failure<E : Throwable>(val error: E) : Result<Nothing, E>()
}
```

Key helpers:
- `map` — transform success value, preserve failure
- `onSuccess` / `onFailure` — side-effect branching
- `getOrNull()` / `exceptionOrNull()` — nullable unwrap
- `EmptyResult<E>` — type alias for `Result<Unit, E>`
- `runCatchingResult { }` — try/catch to `Result<T, E>`

This is used throughout the config layer (`ServiceConfig.fromMapOrError`), `ConfigService`, and other places that need typed errors without exceptions.

Typed error hierarchies used:
- `TrackerError` — sealed class (`MissingApiKey`, `GraphQlErrors`, `Request`, `UnknownPayload`, `MissingEndCursor`, `StateNotFound`)
- `LinearError` — in `:koncerto-linear`

### Dependency Injection

Spring Boot `@Configuration` / `@Bean` (not Koin) provides DI in `koncerto-app/src/main/kotlin/.../app/Beans.kt`. Key wiring decisions:

- **Coroutine scope** (`appScope`): `CoroutineScope(SupervisorJob() + Dispatchers.IO)` — shared across the app
- **ServiceConfig**: constructed eagerly from workflow file YAML via `WorkflowLoader.loadFromPath`, validated with deprecation warnings
- **LinearClient factory**: `(ProjectConfig) -> LinearClient` lambda, wraps `LinearGraphQLClient` in `RateLimitedLinearClient` when rate limiting/circuit breaker is configured
- **AgentRunner**: `DefaultAgentRunner` with callbacks for output capture, circuit breaker, error tracker, health checker, retry config
- **CompositeNotifier**: assembles `LoggingNotifier` + optional `WebhookNotifier`, `TelegramNotifier`, `SmtpEmailNotifier` from project config

### Coroutine Dispatcher Ownership

Each module owns its dispatcher decisions. The `:koncerto-app` beans define `Dispatchers.IO` for the main scope. The `:koncerto-orchestrator` `DispatchService` uses `withContext(Dispatchers.IO)` for blocking I/O (Linear API calls, file reads). The `:koncerto-core` `RateLimitProvider` uses `withContext(Dispatchers.IO)` for refill operations.

No hardcoded `Dispatchers.IO` calls exist in library modules that would prevent testing — the orchestrator and core modules accept dispatchers contextually where needed via `withContext`.

## 3. Testing Strategy

### ViewModel Tests

There are no Android ViewModel tests. The equivalent is **service-level unit tests** for orchestrator and core components. The pattern is JUnit5 + AssertK:

- `runBlocking` or `runBlocking` for coroutine tests
- `assertk.assertThat` with `.isTrue()`, `.isFalse()`, `.isEqualTo()`, `.isNull()`, `.isSameAs()`
- No TestCoroutineDispatcher usage (uses live dispatchers)

### Repository Tests

Repositories and services are tested with **fake data sources**. Examples:
- `CircuitBreakerTest` — pure unit with `Thread.sleep` for timing
- `TokenBucketRateLimiterTest` — unit with `Thread.sleep` for refill intervals and `runBlocking` for `acquire`
- `ResultTest` — pure unit testing sealed class methods
- `ServiceConfigTest` — config parsing from maps
- `RateLimitMonitorTest` — pure unit with stat objects
- `ErrorTrackerTest` — pure unit testing `DefaultErrorTracker`
- `EventBusTest` — `runBlocking` with `first()` on SharedFlow
- `QuotaEnforcerTest` — unit testing concurrent access patterns
- `TenantResolverTest` — unit testing resolution logic

### Integration Tests

- `:koncerto-app` tests (`BeansTest`, `CliRunnerTest`, `OrchestratorHealthIndicatorTest`, `SpringBootContextTest`) — Spring Boot context with `@SpringBootTest`
- `:koncerto-e2e` (`BlockedIssuesE2eTest`, `CodexE2eTest`, `OpenCodeE2eTest`) — full end-to-end with external agent invocations
- `:koncerto-dashboard` tests (`ApiV1ControllerTest`, `DashboardControllerTest`, `AdminControllerTest`) — controller-level with Spring WebFlux
- `:koncerto-notifications` tests (`NotifierTest`, `LoggingNotifierTest`, `WebhookNotifierTest`, `TelegramNotifierTest`, `SmtpEmailNotifierTest`, `NotificationEventTest`) — testing each channel

### Fakes vs Mocks

The project **prefers fakes** over mocks:
- `CircuitBreaker` — real instance with controlled thresholds
- `TokenBucketRateLimiter` — real instance with short intervals
- No MockK usage in core tests (though MockK is available as `testImplementation`)
- Spring tests use `@MockkBean` / `@MockBean` where needed

### Test Configuration

Root `build.gradle.kts` applies `useJUnitPlatform()` to all subproject `Test` tasks. JaCoCo is opt-in via `-Pjacoco` project property.

```
./gradlew test                                          # all tests
./gradlew test -Pjacoco                                 # all tests with coverage
./gradlew :koncerto-core:test                           # single module
./gradlew uiTest                                        # Playwright UI tests (excluded from default)
```

### Known Pre-existing Test Issues

The following test files have pre-existing compilation errors and are excluded from the CI pipeline pending refactoring:

- `SubtaskManifestTest` (`koncerto-core/.../config/SubtaskManifestTest.kt`)
- `GitWorkflowBranchTest` (`koncerto-workspace/.../workspace/GitWorkflowBranchTest.kt`)
- `DispatchServiceTest` (exists separately from `DispatchService.kt`)

## 4. Performance Considerations

### Rate Limiting

**Token bucket algorithm** in `koncerto-core/src/main/kotlin/.../core/TokenBucketRateLimiter.kt`:

```kotlin
class TokenBucketRateLimiter(
    private val maxTokens: Int,
    private val refillIntervalMs: Long,
    private val refillCount: Int
)
```

- `acquire()` — suspends until a token is available (polls at `refillIntervalMs / 4`)
- `tryAcquire()` — non-blocking, returns `Boolean`
- Uses `AtomicLong` for thread-safe token tracking
- Refill: `intervals = elapsed / refillIntervalMs`, adds `intervals * refillCount` tokens, caps at `maxTokens`

**Per-provider rate limiting** in `koncerto-core/src/main/kotlin/.../core/ratelimit/RateLimitProvider.kt`:

- Configurable per-minute and per-hour limits
- Token refill at 1-second granularity
- `RateLimitRegistry` singleton manages named providers
- `RateLimitMonitor` interface with threshold-based alerting (e.g., warn at 75% usage)

### Circuit Breakers

**Per-agent-key circuit breaker** in `koncerto-core/src/main/kotlin/.../core/agent/AgentCircuitBreaker.kt`:

- Per-agent-key state machine: `CLOSED` / `OPEN` / `HALF_OPEN`
- Configurable `failureThreshold` and `resetTimeoutMs`
- `ConcurrentHashMap` of `BreakerState` instances — one per agent key
- Used by `DefaultAgentRunner` to prevent launching agents on failing providers

**Provider-level circuit breaker** in `koncerto-core/src/main/kotlin/.../core/circuitbreaker/ProviderCircuitBreaker.kt`:

- Same CLOSED/OPEN/HALF_OPEN states with `CircuitBreakerConfig`
- HALF_OPEN allows configurable `halfOpenMaxAttempts` before rejecting again
- `CircuitBreakerRegistry` singleton for named provider breakers

**Decorator-based circuit breaker** in `koncerto-linear/.../linear/RateLimitedLinearClient.kt`:

- Wraps `LinearClient` delegate with rate limiter + circuit breaker
- Delegates all 11 `LinearClient` methods through `protect { }`
- On failure: `circuitBreaker.recordFailure()`
- On success: `circuitBreaker.recordSuccess()`

### Exponential Backoff with Jitter

`koncerto-core/src/main/kotlin/.../core/retry/RetryStrategy.kt`:

```kotlin
data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 60_000,
    val multiplier: Double = 2.0,
    val jitterFactor: Double = 0.2
)
```

- `nextDelay(attempt)` = `min(initialDelayMs * multiplier^attempt, maxDelayMs) + randomJitter`
- Jitter: `delay * jitterFactor * Math.random()`
- `retryWithBackoff()` — loops attempts with configurable `shouldRetry` predicate

`RetryExecutor` in `:koncerto-orchestrator` provides a simpler bit-shift backoff: `10_000L * (1L shl (attempt - 1).coerceAtMost(20))` capped at `maxBackoffMs`.

## 5. Security Decisions

### Admin API Key

- `X-Admin-Key` header authentication on `/api/v1/admin/**` endpoints
- `AdminController.authorized()` compares against `config.adminApiKey`
- Returns 401 on mismatch, 404 for missing resource
- Not applied to `/api/v1/` public endpoints (health, metrics, state)

### Secret Boundaries

- API keys stored in YAML config files with `$ENV_VAR` resolution (`ServiceConfig.resolveEnvRef`)
- Never hardcoded in source code
- `admin.apiKey` read from config map with `(map["admin"] as? Map<*, *>)?.get("apiKey") as? String`
- Environment variable reference pattern: `$LINEAR_API_KEY` or `$GITHUB_TOKEN`

### Health & Actuator Endpoints

- Spring Boot Actuator with `/actuator/health` and `/actuator/prometheus`
- `OrchestratorHealthIndicator` at `koncerto-app/.../app/OrchestratorHealthIndicator.kt`
- Actuator endpoints are open by default (no auth on health/readiness)
- Dashboard endpoints available at `/api/v1/`

## 6. Build & Configuration

### Version Catalog

`gradle/libs.versions.toml` centralizes all dependency versions:

| Library | Version |
|---|---|
| Kotlin | 2.0.21 |
| Spring Boot | 3.3.5 |
| kotlinx-coroutines | 1.9.0 |
| kotlinx-serialization | 1.7.3 |
| SnakeYAML | 2.3 |
| JUnit5 | 5.11.3 |
| AssertK | 0.28.1 |
| MockK | 1.13.13 |
| Micrometer | 1.14.5 |
| Testcontainers | 1.20.3 |
| Playwright | 1.49.0 |

### Gradle Convention Plugins

Each module declares its own plugins and dependencies explicitly. Convention plugins are not extracted — shared configuration lives in the root `build.gradle.kts`:

- `subprojects { apply(plugin = "jacoco") }` — JaCoCo coverage for all modules
- `useJUnitPlatform()` — all subprojects use JUnit5
- `kotlin("jvm")` and `kotlin("plugin.serialization")` applied per-module
- Spring Boot plugins (`org.springframework.boot`, `io.spring.dependency-management`) only in `:koncerto-app`

### Project Configuration

YAML-based configuration loaded from a workflow file (path configured via `koncerto.workflow-path`). The format uses frontmatter:

```yaml
---
poll_interval_ms: 30000
projects:
  my-project:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY
      project_slug: my-slug
    agent:
      kind: opencode
      max_turns: 20
    notifications:
      on_completed: true
---
<prompt template>
```

Key configuration data classes in `:koncerto-core`:
- `ServiceConfig` — top-level config with projects map, hooks, git, admin API key
- `ProjectConfig` — per-project config with tracker, workspace, agent, rate limiter, circuit breaker, notifications, tenant, quota
- `WorkplanConfig` — execution mode (SEQUENTIAL/PARALLEL), max parallel subagents
- `RateLimiterConfig` / `CircuitBreakerConfig` / `NotificationsConfig` — nested config blocks
- `RateLimitConfig` — per-provider (linear, github, agent) with per-minute/per-hour limits + burst
- `RoutingRule` — conditional agent routing by label, state, priority
- `StageAgentConfig` / `AgentProviderConfig` — multi-stage workflow agents
- `FollowUpConfig` / `CrossProjectFollowUpConfig` — issue follow-up creation

## 7. Challenges & Resolutions

### Parallel Agent File Conflicts

**Problem:** Multiple AI agents (subagents) writing to shared resources (workspace, git) caused file conflicts, lost work, and inconsistent state.

**Resolution:**
- `AgentMessageStore` (`koncerto-orchestrator/.../orchestrator/AgentMessageStore.kt`) — in-memory message queue with `ConcurrentLinkedQueue` and `ConcurrentHashMap`
- Message lifecycle: `sendMessage()` (producer) -> `pollMessages()` / `waitForMessages()` (consumer) -> `ackMessage()` (consumer)
- Backpressure: `maxMessagesPerAgent` cap (1000), 100-message limit per poll, `MutableSharedFlow` with 100 buffer
- `AtomicReference` for shared state updates in `RuntimeState`
- `ConcurrentHashMap` throughout dispatch service, circuit breakers, and rate limiters

### SQLDelight vs Room for KMP

**Problem:** The project needed a portable embedded database for metrics. Considered SQLDelight (for KMP) vs Room.

**Resolution:** Neither was chosen. The project uses **raw SQLite via JDBC** (`SqliteMetricsRepository` in `:koncerto-metrics`) since the project is JVM-only (no KMP). This avoids both ORM overhead and KMP complexity. The `MetricsRepository` interface at `koncerto-metrics/.../metrics/MetricsRepository.kt` abstracts the storage layer.

### DispatchService Growing Too Large

**Problem:** `DispatchService.kt` grew to 641 lines with multiple concerns: agent routing, issue lifecycle, retry logic, follow-up creation, cross-project chaining, clarification handling, notifications.

**Resolution:**
- `RuntimeState` extracted to own file for state management
- `FollowUpRenderer` handles follow-up issue creation
- `CrossProjectChainer` handles cross-project task creation
- `SubtaskFrontier` and `SubtaskOrchestrator` handle subtask decomposition
- `AgentMessageStore` handles inter-agent communication
- `RetryExecutor` handles retry scheduling
- `WorkplanParser` handles workplan deserialization
- `DependencyGraph` handles graph building for visualization

Despite this, `DispatchService` remains the largest file in the project and is a candidate for further decomposition.

### Pre-existing Test Compilation Errors

**Problem:** `SubtaskManifestTest`, `GitWorkflowBranchTest`, and `DispatchServiceTest` had pre-existing compilation errors (referencing types that no longer exist or were restructured).

**Resolution:** These tests are excluded from CI. They should be either:
1. Rewritten to match current API surfaces, or
2. Removed if the tested functionality is covered elsewhere.

## 8. Developer Onboarding Notes

### Adding a New Module

1. Create `koncerto-<name>/build.gradle.kts`:
```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")  // if using @Serializable
}

dependencies {
    implementation(project(":koncerto-core"))
    // ... other dependencies
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

2. Add to `settings.gradle.kts`:
```kotlin
include("koncerto-<name>")
```

3. Wire beans in `koncerto-app/.../app/Beans.kt`
4. Add module dependency to `:koncerto-app` in `koncerto-app/build.gradle.kts`

### Adding Tests

- **Core logic tests**: JUnit5 + AssertK in same module under `src/test/kotlin/`
- **Spring integration tests**: `@SpringBootTest` in `:koncerto-app`
- **Controller tests**: Spring WebFlux `WebTestClient` in `:koncerto-dashboard`
- **E2E tests**: in `:koncerto-e2e` with external agent invocation
- **UI tests**: Playwright in `:koncerto-app`, tagged with `@Tag("ui")`, run via `./gradlew uiTest`

### Configuration File Format

Default location: workflow file with YAML frontmatter. Key fields:

```yaml
poll_interval_ms: 30000
admin:
  apiKey: $ADMIN_API_KEY
projects:
  <slug>:
    tracker:
      kind: linear | github
      api_key: $TRACKER_API_KEY
      project_slug: <linear-team-slug>
    agent:
      kind: opencode | codex
      max_turns: 20
      max_concurrent_agents: 2
    rate_limiter:
      requests_per_second: 10
      max_burst: 20
    circuit_breaker:
      failure_threshold: 5
      reset_timeout_ms: 30000
```

### Build Commands

```bash
./gradlew build                # full build
./gradlew test                 # run all unit tests
./gradlew test -Pjacoco        # run with coverage
./gradlew uiTest               # Playwright UI tests
./gradlew :koncerto-core:test  # single module
./gradlew clean build          # clean rebuild
```

### Key Production Configurations

- **`koncerto.workflow-path`** — path to workflow YAML file (required)
- **`koncerto.logs-root`** — directory for file logging (optional, defaults to stderr only)
- **`koncerto.db.path`** — SQLite metrics database path (defaults to `~/.koncerto/metrics.db`)

### Module Source Locations

| Module | Source Root | Key Files |
|---|---|---|
| `:koncerto-core` | `core/src/main/kotlin/.../core/` | `Result.kt`, `TokenBucketRateLimiter.kt`, `CircuitBreaker.kt`, `ServiceConfig.kt`, `ProjectConfig.kt`, `tracker/TrackerClient.kt`, `ratelimit/`, `circuitbreaker/`, `errors/`, `events/`, `quota/`, `tenant/`, `retry/`, `agent/`, `config/` |
| `:koncerto-logging` | `logging/src/main/kotlin/.../logging/` | `StructuredLogger.kt`, `LogSinks.kt` |
| `:koncerto-workflow` | `workflow/src/main/kotlin/.../workflow/` | `WorkflowLoader.kt`, `WorkflowCache.kt`, `FrontMatterParser.kt`, `PromptRenderer.kt` |
| `:koncerto-workspace` | `workspace/src/main/kotlin/.../workspace/` | `WorkspaceManager.kt`, `GitWorkflow.kt`, `HookExecutor.kt`, `WorkspaceKey.kt` |
| `:koncerto-linear` | `linear/src/main/kotlin/.../linear/` | `LinearClient.kt`, `LinearGraphQLClient.kt`, `RateLimitedLinearClient.kt`, `LinearError.kt`, `IssueMapper.kt` |
| `:koncerto-agent` | `agent/src/main/kotlin/.../agent/` | `AgentRunner.kt`, `AgentHealthChecker.kt`, `AgentRuntimeFactory.kt` |
| `:koncerto-orchestrator` | `orchestrator/src/main/kotlin/.../orchestrator/` | `Orchestrator.kt`, `DispatchService.kt`, `RuntimeState.kt`, `RetryExecutor.kt`, `DependencyGraph.kt`, `AgentMessageStore.kt` |
| `:koncerto-dashboard` | `dashboard/src/main/kotlin/.../dashboard/` | `ApiV1Controller.kt`, `DashboardController.kt`, `admin/AdminController.kt`, `admin/ProjectRegistry.kt` |
| `:koncerto-metrics` | `metrics/src/main/kotlin/.../metrics/` | `MetricsRepository.kt`, `SqliteMetricsRepository.kt`, `PrometheusMetricsBinder.kt`, `IssueMetrics.kt` |
| `:koncerto-notifications` | `notifications/src/main/kotlin/.../notifications/` | `Notifier.kt`, `NotificationEvent.kt`, `channel/` |
| `:koncerto-app` | `app/src/main/kotlin/.../app/` | `KoncertoApplication.kt`, `Beans.kt`, `ConfigService.kt`, `CliRunner.kt`, `OrchestratorHealthIndicator.kt` |
| `:koncerto-e2e` | `e2e/src/test/kotlin/.../e2e/` | `BlockedIssuesE2eTest.kt`, `CodexE2eTest.kt`, `OpenCodeE2eTest.kt` |
