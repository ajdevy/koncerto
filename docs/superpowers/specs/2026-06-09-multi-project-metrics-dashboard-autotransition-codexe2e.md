# Multi-Project, Metrics, Dashboard Polish, Stage Auto-Transition, and Codex E2E

## Summary

Five independent features extending the koncerto agent orchestration platform.
All can be designed in parallel; implementation ordering respects dependency
constraints (metrics before dashboard history, multi-project before stage
per-project staging).

---

## Feature 1: Stage Auto-Transition

### Motivation

After a stage agent completes successfully, the issue should automatically
move to the configured `on_complete_state` (e.g., "In Review" after "Todo").
Currently the issue stays in its current state and requires manual transition.

### Design

After `agentRunner.run()` succeeds in `DispatchService.dispatch()`, check if
the current stage has an `on_complete_state`. If so, call
`linearClient.updateIssueState(issue.id, onCompleteState)`. If the target
state equals the current state, skip (no-op).

### API changes

`LinearClient` gets a new method:

```kotlin
suspend fun updateIssueState(issueId: String, state: String): EmptyResult<DataError>
```

GraphQL mutation:

```graphql
mutation UpdateIssueState($id: String!, $stateId: String!) {
  issueUpdate(id: $id, input: { stateId: $stateId }) {
    success
  }
}
```

How to resolve state name → state ID: Linear's `workflowStates` query returns
all states for a team by name. Cache at startup.

### State: config

`StageAgentConfig` already has `onCompleteState: String?` — no schema change.

### Files

| File | Change |
|------|--------|
| `koncerto-linear/.../LinearClient.kt` | Add `updateIssueState()`, `resolveStateId()` |
| `koncerto-orchestrator/.../DispatchService.kt` | Call after successful agent run |
| `koncerto-linear/src/test/...` | Tests for new methods |

---

## Feature 2: Codex E2E

### Motivation

The existing E2E test (`KoncertoE2eTest`) only exercises the opencode runtime.
The Codex runtime path is untested end-to-end.

### Design

Parameterize `KoncertoE2eTest` to run with both `agent.kind = opencode` and
`agent.kind = codex`. Gate the Codex variant behind `-Dkoncerto.e2e.codex=true`
(default false, matching the opencode gate).

The test spawns a real codex subprocess, writes an issue to disk, verifies the
agent processes it (JSON-RPC handshake, turn completion).

### Files

| File | Change |
|------|--------|
| `koncerto-e2e/.../KoncertoE2eTest.kt` | Parameterize by agent kind |

---

## Feature 3: Persistent Metrics

### Motivation

All agent run data is in-memory (`RuntimeState`). No history survives restarts.
Aggregate metrics (total tokens, run count, last result) should persist to SQLite.

### Design

New module `koncerto-metrics` with `org.xerial:sqlite-jdbc`.

### Schema

```sql
CREATE TABLE issue_metrics (
    issue_id TEXT PRIMARY KEY,
    issue_identifier TEXT NOT NULL,
    project_slug TEXT,
    total_runs INTEGER DEFAULT 0,
    total_input_tokens INTEGER DEFAULT 0,
    total_output_tokens INTEGER DEFAULT 0,
    total_tokens INTEGER DEFAULT 0,
    last_result TEXT,         -- 'success', 'failure', 'clarification'
    last_run_at TEXT,         -- ISO-8601
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
```

### Repository

```kotlin
interface MetricsRepository {
    suspend fun updateAfterRun(issue: Issue, result: String, tokens: TokenUsage)
    suspend fun findByProject(projectSlug: String?): List<IssueMetrics>
    suspend fun findAll(): List<IssueMetrics>
    suspend fun tokenHistory(days: Int = 30): List<TokenDaySummary>
}
```

Implementation uses `java.sql.Connection` via `sqlite-jdbc`. Thread-safe via
synchronized connection or connection-per-operation.

### Wiring

`DispatchService.onSuccess` / `onFailure` call `metricsRepository.updateAfterRun()`.
Bean in `koncerto-app/Beans.kt`.

### Files

| File | Change |
|------|--------|
| `koncerto-metrics/build.gradle.kts` | New module, depends on core, sqlite-jdbc |
| `koncerto-metrics/.../MetricsRepository.kt` | Interface + SQLite impl |
| `koncerto-metrics/.../IssueMetrics.kt` | Data class |
| `koncerto-metrics/.../TokenDaySummary.kt` | Data class |
| `settings.gradle.kts` | Include koncerto-metrics |
| `koncerto-app/.../Beans.kt` | Wire MetricsRepository |
| `koncerto-orchestrator/.../DispatchService.kt` | Call repository |
| `koncerto-app/build.gradle.kts` | Add :koncerto-metrics dependency |

---

## Feature 4: Multi-Project Support

### Motivation

Single koncerto instance should watch multiple Linear projects, each with its
own workspace, agent config, stages, and concurrency limits.

### Design

Breaking config change: flat root-level fields move under `projects:<slug>:`.

### Config format

```yaml
poll_interval_ms: 30000
max_retry_backoff_ms: 300000

projects:
  frontend:
    tracker:
      kind: linear
      endpoint: https://api.linear.app/graphql
      api_key: ${LINEAR_API_KEY}
      project_slug: frontend-app
    workspace:
      root: /tmp/workspaces/frontend
    agent:
      kind: opencode
      command: opencode
      max_concurrent_agents: 3
      max_turns: 20
      turn_timeout_ms: 3600000
      stages:
        "In Progress":
          prompt: prompts/implement.md
          model: anthropic/claude-sonnet-4-5
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          on_complete_state: "Done"
```

Global fields (shared):
- `poll_interval_ms`
- `max_retry_backoff_ms`
- Linear API connection (if same across projects, can be shared)

### Architecture

Single `Orchestrator` with `Map<String, ProjectConfig>` keyed by project slug.
One `RuntimeState` per project. One poll loop fetches issues from all projects,
routes each issue to its project's config.

```kotlin
class Orchestrator(
    private val projects: Map<String, ProjectConfig>,
    private val linearClient: LinearClient,
    private val agentRunner: AgentRunner,
    private val scope: CoroutineScope
) {
    private val states: Map<String, RuntimeState>
    
    fun start() {
        scope.launch { pollLoop() }
    }
    
    private suspend fun pollLoop() {
        while (true) {
            for ((slug, config) in projects) {
                val issues = linearClient.fetchIssues(config.tracker)
                dispatchForProject(slug, config, issues)
            }
            delay(config.pollIntervalMs)
        }
    }
}
```

### Backward compatibility

Breaking change: flat config is no longer supported. `ServiceConfig` becomes
`Map<String, ProjectConfig>`.

### Files

| File | Change |
|------|--------|
| `koncerto-core/.../config/ServiceConfig.kt` | Rewrite to `Map<String, ProjectConfig>` |
| `koncerto-core/.../config/ProjectConfig.kt` | New — per-project config |
| `koncerto-orchestrator/.../Orchestrator.kt` | Accept project map, per-project state |
| `koncerto-orchestrator/.../DispatchService.kt` | Accept project config |
| `koncerto-orchestrator/.../RuntimeState.kt` | Keyed by project slug |
| `koncerto-app/.../Beans.kt` | Wire project map |
| `koncerto-app/.../CliRunner.kt` | Start single orchestrator |
| `WORKFLOW.md` | Update config format |
| All tests | Update to new config structure |

---

## Feature 5: Dashboard Polish

### Motivation

Current dashboard is minimal — running/retrying agents and output streaming.
Needs run history, token charts, agent controls, stage concurrency view, and
filtering/search.

### Tech

- **Framework**: Vanilla JS + Chart.js CDN (no build step)
- **Layout**: Single scrollable page with section navigation
- **API**: New endpoints + enhanced existing ones

### API Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET` | `/api/v1/history?project=&state=&limit=` | Completed runs from SQLite metrics |
| `PUT` | `/api/v1/running/{id}/pause` | Pause running agent |
| `PUT` | `/api/v1/running/{id}/resume` | Resume paused agent |
| `PUT` | `/api/v1/running/{id}/cancel` | Cancel running agent |
| `GET` | `/api/v1/stages` | Per-project stage config + usage |

### Agent controls

Pause: set flag on `RunningEntry`, check before starting next turn.
Resume: clear pause flag.
Cancel: kill process, remove from `RuntimeState`.

### Dashboard sections

| Section | Source | Widget |
|---------|--------|--------|
| **Running** | `/api/v1/state` | Table + pause/resume/cancel buttons |
| **History** | `/api/v1/history` | Sortable table, filter by project/state |
| **Concurrency** | `/api/v1/stages` | Per-project, per-stage usage bars (e.g., `In Progress: 2/3`) |
| **Tokens** | `/api/v1/history` | Chart.js line chart — tokens/day |

### Files

| File | Change |
|------|--------|
| `koncerto-dashboard/.../ApiV1Controller.kt` | Add history, stages, pause/resume/cancel endpoints |
| `koncerto-dashboard/.../dashboard.html` | New sections, Chart.js, controls |
| `koncerto-orchestrator/.../RuntimeState.kt` | Add pause/cancel support |
| `koncerto-orchestrator/.../AgentRunner.kt` | Add pause check |

---

## Dependency Graph

```
auto-transition ──┐
                  ├──> all independent in design
Codex E2E ────────┤
metrics ──────────┤
multi-project ────┤
dashboard ────────┘

Implementation ordering constraints:
  metrics → dashboard history (needs SQLite data)
  multi-project → stage concurrency view (needs per-project stages)
  auto-transition → multi-project (stages are per-project)
```

## Out of Scope

- OAuth/auth for dashboard
- Persistent audit log (beyond aggregate metrics)
- WebSocket for real-time dashboard updates (SSE is sufficient)
- Agent-to-agent communication
- UI theme customization

## Testing

- **Auto-transition**: Unit test `DispatchServiceTest` — verify `linearClient.updateIssueState` called on success with correct state
- **Codex E2E**: Parameterized `KoncertoE2eTest` with `agent.kind=codex`
- **Metrics**: `MetricsRepositoryTest` — SQLite in-memory, verify CRUD
- **Multi-project**: `OrchestratorTest` — verify routing by slug, per-project state isolation
- **Dashboard**: Playwright UI test updates (new sections, controls)
