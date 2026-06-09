# Architecture Document: Koncerto

**Version:** 1.1  
**Date:** 2026-06-09  
**Architect:** Fred the Architect  
**Status:** Updated (v1.1: dependency tracking, routing, chaining)  

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
| Testing | JUnit 5, AssertK | — | Modern, expressive |
| Coverage | JaCoCo | — | Industry standard |

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

| Module | Responsibility | Key Classes | Dependencies |
|--------|---------------|-------------|--------------|
| koncerto-core | Shared types, config parsing | Result, Issue, ServiceConfig, WorkflowDefinition | None |
| koncerto-logging | Structured logging | StructuredLogger, StderrSink, FileSink | core |
| koncerto-workflow | YAML parsing, template rendering | FrontMatterParser, PromptRenderer, WorkflowCache | core |
| koncerto-workspace | Workspace isolation, hooks | WorkspaceManager, ShellHookExecutor, WorkspaceKey | core, logging |
| koncerto-linear | Linear GraphQL integration | LinearGraphQLClient, DefaultLinearClient, IssueMapper | core |
| koncerto-agent | Agent abstraction & runtimes | AgentRuntime, AgentRuntimeFactory, CodexRuntime, OpencodeRuntime, AgentEvent | core, logging, workflow, workspace |
| koncerto-orchestrator | Poll loop, dispatch, retry | Orchestrator, RuntimeState | core, logging, workflow, workspace, agent, linear |
| koncerto-dashboard | REST API, HTML dashboard | ApiV1Controller, DashboardController | core, orchestrator |
| koncerto-app | Application entry point | KoncertoApplication, Beans, CliRunner | All modules |

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
   - Select agent runtime via AgentRuntimeFactory (Codex or opencode)
   - Start agent via AgentRuntime.run()
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

## 14. Future Considerations

| Area | Options | Trade-offs |
|------|---------|------------|
| Multi-project | Support multiple Linear projects | Complexity vs flexibility |
| Agent types | Support additional agent runtimes | Implementation effort vs value |
| Persistence | Database for audit trail | Storage vs observability |
| Auth | API key or OAuth for dashboard | Security vs simplicity |
| Monitoring | Prometheus metrics | Integration effort vs observability |
