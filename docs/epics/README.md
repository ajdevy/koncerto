# Koncerto — Epic Breakdown

**Version:** 1.0  
**Date:** 2026-06-08  

---

## Epic Overview

| Epic | Name | Description | Status |
|------|------|-------------|--------|
| E1 | Core Foundation | Result wrapper, Issue model, ServiceConfig | ✅ Complete |
| E2 | Structured Logging | Multi-sink logging infrastructure | ✅ Complete |
| E3 | Workflow Engine | YAML front matter parsing, Liquid templates | ✅ Complete |
| E4 | Workspace Management | Isolated workspaces, hook execution | ✅ Complete |
| E5 | Linear Integration | GraphQL client, issue mapping | ✅ Complete |
| E6 | Agent Runtime | JSON-RPC, Codex subprocess, event streaming | ✅ Complete |
| E7 | Orchestration | Poll loop, dispatch, retry, reconciliation | ✅ Complete |
| E8 | Dashboard & API | REST endpoints, HTML dashboard | ✅ Complete |
| E9 | Application Assembly | Spring Boot app, CLI runner, bean wiring | ✅ Complete |

---

## Epic 1: Core Foundation

**Goal:** Establish type-safe foundations shared across all modules.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 1.1 | As a developer, I want a Result wrapper so that errors are explicitly handled | `Result<T, E>` supports `map`, `onSuccess`, `onFailure`, `getOrNull` | P0 |
| 1.2 | As a developer, I want an Issue model so that tracker data is strongly typed | `Issue` data class with all Linear fields, `normalizedState` property | P0 |
| 1.3 | As a developer, I want a WorkflowDefinition so that config is parsed from YAML | Parse front matter from `WORKFLOW.md`, validate required fields | P0 |
| 1.4 | As a developer, I want a ServiceConfig so that runtime settings are centralized | `ServiceConfig` with env resolution, path expansion, validation | P0 |
| 1.5 | As a developer, I want typed error classes so that failures are categorized | `LinearError`, `AgentError`, `WorkspaceError` hierarchies | P1 |

### Technical Notes
- Use `kotlinx.serialization` for JSON
- `Result` is custom, not stdlib `kotlin.Result`
- `ServiceConfig.fromMapOrError` returns `Result<ServiceConfig, IllegalStateException>`

---

## Epic 2: Structured Logging

**Goal:** Provide observable, structured logging across all modules.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 2.1 | As a developer, I want a StructuredLogger so that logs are machine-parseable | Key-value pair logging with levels (info, warn, failure) | P0 |
| 2.2 | As an operator, I want stderr output so that logs appear in container logs | `StderrSink` writes JSON lines to stderr | P0 |
| 2.3 | As an operator, I want file output so that logs persist across restarts | `FileSink` writes to rotating log files | P1 |
| 2.4 | As an operator, I want composite sinks so that logs go to multiple destinations | `CompositeSink` fans out to multiple sinks | P1 |

### Technical Notes
- StructuredLogger is immutable, accepts list of sinks
- Sinks are: `StderrSink`, `FileSink`, `CompositeSink`
- Each log call includes: level, event name, key-value context

---

## Epic 3: Workflow Engine

**Goal:** Parse workflow definitions and render prompts with issue context.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 3.1 | As a developer, I want YAML front matter parsing so that config is separated from content | Parse `---` delimited YAML from markdown files | P0 |
| 3.2 | As a developer, I want Liquid template rendering so that prompts include issue data | `{{ issue.identifier }}`, `{{ issue.title }}` placeholders | P0 |
| 3.3 | As a developer, I want a WorkflowCache so that templates are loaded once | Cache current workflow, support reload | P1 |

### Technical Notes
- Use `liqp` library for Liquid rendering
- Unknown variables are silently removed (liqp behavior)
- PromptRenderer validates variable references before rendering

---

## Epic 4: Workspace Management

**Goal:** Create isolated workspaces with lifecycle hooks.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 4.1 | As a developer, I want workspace creation so that agents have isolated dirs | Create directory under configured root, named by issue identifier | P0 |
| 4.2 | As an operator, I want shell hooks so that setup/teardown runs automatically | Execute `after_create`, `before_run`, `after_run`, `before_remove` | P0 |
| 4.3 | As a developer, I want workspace removal so that disk space is reclaimed | Remove workspace directory and contents on completion | P0 |
| 4.4 | As an operator, I want hook timeouts so that hangs don't block the system | Configurable timeout for hook execution | P1 |

### Technical Notes
- `WorkspaceManager` uses `WorkspaceKey` (identifier + root)
- `ShellHookExecutor` runs commands via `ProcessBuilder`
- Hooks have configurable timeout (default 60s)

---

## Epic 5: Linear Integration

**Goal:** Fetch issues from Linear via GraphQL.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 5.1 | As a developer, I want to fetch candidate issues so that work can be dispatched | Query issues by project slug and active states | P0 |
| 5.2 | As a developer, I want to fetch issue states so that reconciliation works | Query states by issue IDs | P0 |
| 5.3 | As a developer, I want issue mapping so that Linear responses become `Issue` objects | Map GraphQL response to `Issue` with blockers, labels | P0 |
| 5.4 | As a developer, I want pagination support so that large projects work | Handle `endCursor` for paginated queries | P1 |

### Technical Notes
- `LinearGraphQLClient` uses Spring WebClient
- Executes on `Dispatchers.IO` to avoid reactor deadlock
- `IssueMapper.fromLinear` handles null fields gracefully

---

## Epic 6: Agent Runtime

**Goal:** Manage Codex subprocess lifecycle via JSON-RPC.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 6.1 | As a developer, I want JSON-RPC framing so that messages are parsed correctly | Parse `Content-Length` framed JSON-RPC messages | P0 |
| 6.2 | As a developer, I want event streaming so that agent activity is visible | Emit `AgentEvent` sealed class variants | P0 |
| 6.3 | As a developer, I want subprocess management so that agents are isolated | Spawn `codex app-server` via `ProcessBuilder` | P0 |
| 6.4 | As a developer, I want turn timeout so that hangs don't block forever | Configurable timeout per turn | P0 |
| 6.5 | As a developer, I want stall detection so that unresponsive agents are killed | Detect no-output conditions and terminate | P1 |

### Technical Notes
- `AgentEvent` is a sealed class with 12 variants
- `CodexAppServerClient` manages stdin/stdout pipes
- `DefaultAgentRunner` orchestrates the full lifecycle

---

## Epic 7: Orchestration

**Goal:** Poll, dispatch, retry, and reconcile issues.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 7.1 | As an operator, I want polling so that new issues are discovered | Poll Linear at configurable interval | P0 |
| 7.2 | As a developer, I want dispatch so that issues are assigned to agents | Create workspace, render prompt, start agent | P0 |
| 7.3 | As a developer, I want retry so that transient failures recover | Exponential backoff with configurable max | P0 |
| 7.4 | As a developer, I want reconciliation so that completed issues are detected | Check running issues against tracker terminal states | P0 |
| 7.5 | As an operator, I want concurrency limits so that resources are bounded | Max concurrent agents, per-state limits | P0 |
| 7.6 | As a developer, I want priority sorting so that important issues run first | Sort by priority, then creation date | P1 |

### Technical Notes
- `Orchestrator` uses structured coroutine scope
- `RuntimeState` tracks running, claimed, retry, completed sets
- Retry uses exponential backoff: `10s * 2^(attempt-1)`, capped at max

---

## Epic 8: Dashboard & API

**Goal:** Provide visibility into orchestrator state.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 8.1 | As an operator, I want a JSON API so that state can be queried programmatically | `GET /api/v1/state` returns running, retrying, totals | P1 |
| 8.2 | As an operator, I want an HTML dashboard so that state is visible at a glance | Live-refreshing table at `/` | P2 |
| 8.3 | As an operator, I want issue lookup so that specific issues can be inspected | `GET /api/v1/{identifier}` returns issue details | P1 |
| 8.4 | As an operator, I want manual refresh so that state can be forced | `POST /api/v1/refresh` triggers poll | P1 |

### Technical Notes
- `ApiV1Controller` returns `Mono<ResponseEntity<Map>>`
- `DashboardController` returns `Mono<String>` (Thymeleaf or raw HTML)
- Dashboard auto-refreshes via JavaScript `fetch` loop

---

## Epic 9: Application Assembly

**Goal:** Wire everything together in a runnable Spring Boot application.

### Stories

| ID | Story | Acceptance Criteria | Priority |
|----|-------|---------------------|----------|
| 9.1 | As an operator, I want a CLI runner so that the workflow file is specified | Accept workflow path as CLI argument | P0 |
| 9.2 | As an operator, I want bean wiring so that dependencies are injected | `@Configuration` class with all beans | P0 |
| 9.3 | As an operator, I want a bootable JAR so that deployment is simple | `bootJar` task produces executable JAR | P0 |

### Technical Notes
- `KoncertoApplication` is `@SpringBootApplication`
- `Beans` class defines all `@Bean` methods
- `CommandLineRunner` starts orchestrator on startup

---

## Story Dependency Graph

```
E1 (Core) ─────────────────────────────────────────┐
    │                                               │
    ├── E2 (Logging) ───────────────────────────┐   │
    │                                           │   │
    ├── E3 (Workflow) ──────────────────────┐   │   │
    │                                       │   │   │
    ├── E4 (Workspace) ──────────────┐      │   │   │
    │                                 │      │   │   │
    ├── E5 (Linear) ─────────┐       │      │   │   │
    │                         │       │      │   │   │
    └── E6 (Agent) ──────┐   │       │      │   │   │
                          │   │       │      │   │   │
                          ▼   ▼       ▼      ▼   ▼   ▼
                       E7 (Orchestrator) ──── E8 (Dashboard)
                                              │
                                              ▼
                                         E9 (App)
```

## Velocity Estimates

| Epic | Story Points | Complexity |
|------|-------------|------------|
| E1 | 8 | Low |
| E2 | 5 | Low |
| E3 | 8 | Medium |
| E4 | 8 | Medium |
| E5 | 13 | Medium |
| E6 | 21 | High |
| E7 | 21 | High |
| E8 | 8 | Low |
| E9 | 5 | Low |
| **Total** | **97** | |
