# Koncerto

[![CI](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml/badge.svg)](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml)
[![coverage](.badges/jacoco.svg)](.badges/coverage-summary.json)

A Kotlin/Spring Boot implementation of the [OpenAI Symphony](https://github.com/openai/symphony) orchestration service. Koncerto polls a project tracker (Linear), dispatches coding tasks to an AI agent (Codex) via JSON-RPC, and manages the full lifecycle — retries, reconciliation, and a live dashboard.

## Prerequisites

- **Java 21+** (JDK, not JRE)
- **Gradle 8.x** (wrapper included, no separate install needed)
- **Codex CLI** or **opencode CLI** — the binary must be on your `PATH` (configured via `agent.kind`)
- **Linear account** — a project with issues in active states (e.g. `Todo`, `In Progress`)

## How It Works

```
┌─────────────┐    poll     ┌──────────────┐    dispatch    ┌────────────────┐
│   Linear    │ ◄─────────► │ Orchestrator │ ─────────────► │  AgentRunner   │
│   (API)     │             │              │                │ (Runtime impl) │
└─────────────┘             │  ┌─────────┐ │                │                │
                            │  │ Runtime │ │                │ CodexRuntime   │
                            │  │  State  │ │                │  or            │
                            │  └─────────┘ │                │ OpencodeRuntime│
                            └──────────────┘                └────────────────┘
                                   │
                            ┌──────▼──────┐
                            │  Dashboard  │
                            │  (HTML/JSON)│
                            └─────────────┘
```

1. **Orchestrator** polls Linear every N seconds for issues in active states
2. For each eligible issue (respecting concurrency limits, labels, blockers), it dispatches an **AgentRunner**
3. AgentRunner spawns the configured **agent subprocess** (Codex or opencode) via JSON-RPC over stdio, sends the rendered prompt, and collects events
4. After each turn, the orchestrator checks if the issue moved to a terminal state and reclaims the workspace
5. Failed attempts are retried with exponential backoff
6. A live **Dashboard** exposes `/api/v1/state` and a web UI at `/`

## Quick Start

```bash
# Clone and build
git clone <repo-url> && cd koncerto
./gradlew build

# Run with a workflow file
./gradlew :koncerto-app:bootRun --args="/path/to/WORKFLOW.md"
```

## Configuration

Koncerto reads its config from a `WORKFLOW.md` file with YAML front matter. The root section holds global settings (`poll_interval_ms`, `hooks`, `git`); each project lives under the `projects:` map keyed by slug:

```yaml
---
poll_interval_ms: 30000
hooks:
  timeout_ms: 60000
git:
  enabled: true
  branch_prefix: "feature/"
  auto_commit: true
  auto_push: true
  create_pr: true
  pr_base: main
projects:
  my-project:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY
      project_slug: MYPROJECT
      active_states:
        - Todo
        - In Progress
      terminal_states:
        - Done
        - Cancelled
    workspace:
      root: /tmp/koncerto_workspaces/my-project
    agent:
      kind: opencode
      max_concurrent_agents: 3
      max_turns: 20
      stages:
        Todo:
          prompt: prompts/implement.md
          model: claude-sonnet-4
          on_complete_state: "In Review"
        "In Review":
          prompt: prompts/review.md
          on_complete_state: "Done"
---
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LINEAR_API_KEY` | Linear API key (or set `api_key` in WORKFLOW.md) | — |
| `KONCERTO_WORKFLOW_PATH` | Path to workflow file | `./WORKFLOW.md` |
| `KONCERTO_LOGS_ROOT` | Directory for log files | (stderr only) |
| `KONCERTO_WORKSPACE_ROOT` | Root dir for agent workspaces | `/tmp/symphony_workspaces` |
| `KONCERTO_WEB_TYPE` | `reactive` to enable HTTP dashboard, `none` for headless | `none` |

### Agent Runtimes

Koncerto supports multiple AI agent runtimes via `agent.kind`:

| Provider | `kind` | Command | Protocol |
|----------|--------|---------|----------|
| **Opencode** | `opencode` (default) | `opencode` | JSON-RPC over stdio |
| **Codex** | `codex` | `codex app-server` | JSON-RPC over stdio |

All providers implement the same `AgentRuntime` interface — events, lifecycle management, and error handling work identically regardless of provider.

### Named Agent Providers

You can define multiple agent providers per project and reference them per-stage or via Linear issue labels:

```yaml
agent:
  kind: opencode                    # default fallback
  max_concurrent_agents: 3
  agents:
    fast:
      kind: codex
      command: codex app-server
      max_concurrent: 5
    cheap:
      kind: opencode
      model: claude-sonnet-3-5-haiku
      max_concurrent: 10
  stages:
    Todo:
      prompt: prompts/implement.md
      agent: fast                   # use the "fast" provider for this stage
    "In Review":
      prompt: prompts/review.md
      agent: cheap                  # use the "cheap" provider
```

**Label overrides** on Linear issues override the stage provider:
- `agent:fast` — use the named provider `fast` for this issue regardless of stage
- `model:gpt-4o` — override the resolved provider's model for this issue

## Running

### Headless (default)

```bash
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"
```

### With Dashboard

```bash
./gradlew :koncerto-app:bootRun --args="--port WORKFLOW.md"
# Dashboard at http://localhost:8080
# API at http://localhost:8080/api/v1/state
```

### As a Fat JAR

```bash
./gradlew :koncerto-app:bootJar
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md
```

## API Endpoints

All endpoints are prefixed by project slug: `/api/v1/{project}/...`

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Live HTML dashboard |
| `/api/v1/{project}/state` | GET | JSON snapshot (running, retrying, token totals) |
| `/api/v1/{project}/{identifier}` | GET | Issue details by identifier |
| `/api/v1/{project}/history` | GET | Historical run log |
| `/api/v1/{project}/stages` | GET | Stage-level concurrency and status |
| `/api/v1/{project}/refresh` | POST | Trigger manual refresh |
| `/api/v1/running/{id}/pause` | POST | Pause a running agent |
| `/api/v1/running/{id}/resume` | POST | Resume a paused agent |
| `/api/v1/running/{id}/cancel` | POST | Cancel a running agent |

## Project Structure

```
koncerto/
├── koncerto-core/         Result wrapper, Issue model, ServiceConfig
├── koncerto-logging/      StructuredLogger with multi-sink support
├── koncerto-workflow/     YAML front matter parser, Liquid prompt renderer
├── koncerto-workspace/    Workspace isolation, hook execution
├── koncerto-linear/       Linear GraphQL client, IssueMapper
├── koncerto-agent/        JSON-RPC framing, agent subprocess, AgentRunner
├── koncerto-orchestrator/ Poll loop, dispatch, retry, reconciliation
├── koncerto-metrics/      SQLite-backed persistent metrics store
├── koncerto-dashboard/    HTML dashboard, REST API
├── koncerto-app/          Spring Boot entry point, bean wiring
├── koncerto-e2e/          End-to-end integration tests
├── WORKFLOW.md            Sample workflow configuration
└── scripts/               Utility scripts
```

## Development

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :koncerto-core:test

# Build without tests
./gradlew build -x test

# Generate coverage report
./gradlew jacocoTestReport
# Report at: <module>/build/reports/jacoco/test/html/index.html
```

## License

MIT License. See [LICENSE](LICENSE) for details.
