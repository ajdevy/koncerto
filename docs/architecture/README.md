# Koncerto — Technical Architecture

**Version:** 1.0  
**Date:** 2026-06-08  

---

## 1. System Overview

Koncerto is a Kotlin/Spring Boot application that orchestrates AI coding agents by connecting project trackers (Linear) with agent runtimes (Codex). It follows a modular, layered architecture with strict dependency direction.

## 2. Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Language | Kotlin | 2.0.21 |
| Framework | Spring Boot | 3.3.5 |
| Build | Gradle | 8.10 |
| JDK | OpenJDK | 21+ |
| Concurrency | Kotlin Coroutines | 1.9.0 |
| HTTP Client | Spring WebClient | (via Spring Boot) |
| Serialization | kotlinx.serialization | 1.7.3 |
| Templating | liqp | (Liquid) |
| Testing | JUnit 5, AssertK | — |
| Coverage | JaCoCo | — |

## 3. Module Architecture

### 3.1 Module Dependency Graph

```
                    ┌─────────────────┐
                    │   koncerto-app  │
                    │ (Spring Boot)   │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
     ┌────────────┐  ┌────────────┐  ┌────────────┐
     │ Dashboard  │  │  CLI Runner│  │   Beans    │
     │ Controller │  │            │  │ (DI Config)│
     └─────┬──────┘  └────────────┘  └────────────┘
           │
           ▼
     ┌────────────┐
     │ Orchestrator│
     └─────┬──────┘
           │
    ┌──────┼──────┬──────────┐
    │      │      │          │
    ▼      ▼      ▼          ▼
┌───────┐┌─────┐┌───────┐┌─────────┐
│Linear ││Agent││Workflow││Workspace│
│Client ││Runner││Cache  ││ Manager │
└───┬───┘└──┬──┘└───┬───┘└────┬────┘
    │       │       │         │
    ▼       ▼       ▼         ▼
┌───────┐┌─────┐┌───────┐┌─────────┐
│GraphQL││Codex││FrontM ││ShellHook│
│Client ││JSON ││Parser ││Executor │
└───────┘└──┬──┘└───────┘└─────────┘
            │
            ▼
     ┌────────────┐
     │   Core     │
     │ (Result,   │
     │  Issue,    │
     │  Config)   │
     └────────────┘
```

### 3.2 Module Responsibilities

| Module | Responsibility | Key Classes |
|--------|---------------|-------------|
| `koncerto-core` | Shared types, config parsing | `Result`, `Issue`, `ServiceConfig`, `WorkflowDefinition` |
| `koncerto-logging` | Structured logging | `StructuredLogger`, `StderrSink`, `FileSink` |
| `koncerto-workflow` | YAML parsing, template rendering | `FrontMatterParser`, `PromptRenderer`, `WorkflowCache` |
| `koncerto-workspace` | Workspace isolation, hooks | `WorkspaceManager`, `ShellHookExecutor`, `WorkspaceKey` |
| `koncerto-linear` | Linear GraphQL integration | `LinearGraphQLClient`, `DefaultLinearClient`, `IssueMapper` |
| `koncerto-agent` | Agent subprocess management | `CodexAppServerClient`, `DefaultAgentRunner`, `AgentEvent` |
| `koncerto-orchestrator` | Poll loop, dispatch, retry | `Orchestrator`, `RuntimeState` |
| `koncerto-dashboard` | REST API, HTML dashboard | `ApiV1Controller`, `DashboardController` |
| `koncerto-app` | Application entry point | `KoncertoApplication`, `Beans`, `CliRunner` |

## 4. Data Flow

### 4.1 Issue Lifecycle

```
┌─────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│ Linear  │────►│Orchestr. │────►│  Agent   │────►│ Linear   │
│ (Todo)  │     │ (poll)   │     │ (Codex)  │     │ (Done)   │
└─────────┘     └────┬─────┘     └────┬─────┘     └──────────┘
                     │                │
                     ▼                ▼
               ┌──────────┐    ┌──────────┐
               │ Workspace│    │  Events  │
               │ (create) │    │ (stream) │
               └──────────┘    └──────────┘
```

### 4.2 Dispatch Flow

1. **Poll** → `Orchestrator.tick()` calls `LinearClient.fetchCandidateIssues()`
2. **Filter** → Remove running/claimed, check labels, check blockers
3. **Sort** → Priority ascending, then creation date
4. **Dispatch** → For each eligible issue (respecting concurrency limits):
   - Create workspace via `WorkspaceManager`
   - Execute `after_create` hook
   - Render prompt via `WorkflowCache.current()`
   - Start agent via `AgentRunner.run()`
5. **Monitor** → Agent emits events, orchestrator tracks state
6. **Complete** → Detect terminal state, clean up workspace

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
| `Orchestrator.scope` | Main poll loop + agent dispatch | Application lifetime |
| `runBlocking` | CLI runner, tests | Synchronous entry points |
| `Dispatchers.IO` | HTTP calls, file I/O | Per-operation |

### 5.2 Thread Safety

| Component | Strategy |
|-----------|----------|
| `RuntimeState.running` | `mutableMapOf()` (single-writer via coroutine) |
| `RuntimeState.claimed` | `mutableSetOf()` (single-writer) |
| `RuntimeState.retryAttempts` | `mutableMapOf()` (single-writer) |
| `LinearGraphQLClient.execute` | `withContext(Dispatchers.IO)` for WebClient.block() |

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
| Core | `IllegalStateException` | Config validation failures |
| Linear | `LinearError` | MissingApiKey, Status, GraphQlErrors |
| Agent | `AgentError` | SubprocessFailure, TurnTimeout |
| Workspace | `WorkspaceError` | HookExecutionFailed |

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
| `tracker.api_key` | Required, not blank | `missing_tracker_api_key` |
| `tracker.project_slug` | Required, not blank | `missing_tracker_project_slug` |
| `agent.max_concurrent_agents` | >= 1 | — |
| `agent.max_turns` | >= 1 | — |
| `workspace.root` | Valid path | — |

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

### 8.2 Coverage Targets

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

### 8.3 Test Patterns

| Pattern | Usage |
|---------|-------|
| Fake implementations | `FakeLinearClient`, `FakeAgentRunner` |
| In-memory state | `RuntimeState` in tests |
| Temp directories | `@TempDir` for workspace tests |
| Coroutine test | `runBlocking`, `TestScope` |

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
| Network | Linear API access, Codex binary |

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

## 12. Future Considerations

| Area | Options |
|------|---------|
| Multi-project | Support multiple Linear projects in one instance |
| Agent types | Support different agent runtimes beyond Codex |
| Persistence | Database for audit trail, metrics |
| Auth | API key or OAuth for dashboard |
| Monitoring | Prometheus metrics, distributed tracing |
