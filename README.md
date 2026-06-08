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

Koncerto reads its config from a `WORKFLOW.md` file with YAML front matter:

```yaml
---
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
polling:
  interval_ms: 30000
workspace:
  root: /tmp/koncerto_workspaces
hooks:
  after_create: echo "workspace ready"
  before_run: npm install
  timeout_ms: 60000
agent:
  kind: codex                    # "codex" (default) or "opencode"
  max_concurrent_agents: 5
  max_turns: 20
  max_retry_backoff_ms: 300000
codex:
  command: codex app-server       # used when agent.kind = codex
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
opencode:
  command: opencode               # used when agent.kind = opencode
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LINEAR_API_KEY` | Linear API key (or set `api_key` in WORKFLOW.md) | — |
| `KONCERTO_WORKFLOW_PATH` | Path to workflow file | `./WORKFLOW.md` |
| `KONCERTO_LOGS_ROOT` | Directory for log files | (stderr only) |
| `KONCERTO_WORKSPACE_ROOT` | Root dir for agent workspaces | `/tmp/symphony_workspaces` |
| `KONCERTO_WEB_TYPE` | `reactive` to enable HTTP dashboard, `none` for headless | `none` |

### Agent Providers

Koncerto supports multiple AI agent runtimes. Set `agent.kind` in `WORKFLOW.md` to switch:

| Provider | `agent.kind` | Command config | Protocol |
|----------|-------------|----------------|----------|
| **Codex** | `codex` (default) | `codex.command` | JSON-RPC over stdio (`codex app-server`) |
| **Opencode** | `opencode` | `opencode.command` | JSON-RPC over stdio (`opencode`) |

All providers implement the same `AgentRuntime` interface — events (session start, turn completion, token usage), lifecycle management, and error handling work identically regardless of provider. You can switch providers by changing a single config value.

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

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | Live HTML dashboard |
| `/api/v1/state` | GET | JSON snapshot (running, retrying, token totals) |
| `/api/v1/{identifier}` | GET | Issue details by identifier |
| `/api/v1/refresh` | POST | Trigger manual refresh |

## Project Structure

```
koncerto/
├── koncerto-core/         Result wrapper, Issue model, ServiceConfig
├── koncerto-logging/      StructuredLogger with multi-sink support
├── koncerto-workflow/     YAML front matter parser, Liquid prompt renderer
├── koncerto-workspace/    Workspace isolation, hook execution
├── koncerto-linear/       Linear GraphQL client, IssueMapper
├── koncerto-agent/        JSON-RPC framing, Codex subprocess, AgentRunner
├── koncerto-orchestrator/ Poll loop, dispatch, retry, reconciliation
├── koncerto-dashboard/    HTML dashboard, REST API
├── koncerto-app/          Spring Boot entry point, bean wiring
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
