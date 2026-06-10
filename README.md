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

## Configuration Reference

### Notification Config

Notifications are configured per-project under the `notifications` key. Global event toggles control which events trigger notifications; if any channel is configured, `on_completed`, `on_failed`, and `on_stalled` default to `true`.

```yaml
notifications:
  on_completed: true
  on_failed: true
  on_stalled: true
  on_clarification: false
  telegram:
    bot_token: $TELEGRAM_BOT_TOKEN
    chat_id: "123456789"
  email:
    smtp_host: smtp.example.com
    smtp_port: 587
    username: $SMTP_USERNAME
    password: $SMTP_PASSWORD
    from: koncerto@example.com
    to: team@example.com
  webhook:
    url: https://hooks.example.com/koncerto
    headers:
      X-API-Key: $WEBHOOK_API_KEY
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `on_completed` | bool | `true` (if any channel defined) | Notify when an agent run completes |
| `on_failed` | bool | `true` (if any channel defined) | Notify when an agent run fails |
| `on_stalled` | bool | `true` (if any channel defined) | Notify when an agent stalls |
| `on_clarification` | bool | `true` (if any channel defined) | Notify when agent requests clarification |

**Telegram:**

| Field | Type | Description |
|-------|------|-------------|
| `bot_token` | string | Telegram bot token (supports `$ENV_VAR` refs) |
| `chat_id` | string | Target chat or channel ID |

**Email (SMTP):**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `smtp_host` | string | — | SMTP server hostname |
| `smtp_port` | int | `587` | SMTP server port |
| `username` | string | — | SMTP auth username (supports `$ENV_VAR` refs) |
| `password` | string | — | SMTP auth password (supports `$ENV_VAR` refs) |
| `from` | string | — | Sender email address |
| `to` | string | — | Recipient email address |

**Webhook:**

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `url` | string | — | Webhook endpoint URL |
| `headers` | map | `{}` | Custom headers (values support `$ENV_VAR` refs) |

---

### Rate Limiter Config

Rate limiting is configured per-project under the `rate_limiter` key. When set, it wraps the Linear API client to throttle requests.

```yaml
rate_limiter:
  requests_per_second: 10
  max_burst: 20
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `requests_per_second` | int | `10` | Sustained request rate |
| `max_burst` | int | `20` | Maximum burst size (token bucket capacity) |

---

### Circuit Breaker Config

Circuit breaking is configured per-project under the `circuit_breaker` key. When set, it wraps the Linear API client to stop requests after repeated failures.

```yaml
circuit_breaker:
  failure_threshold: 5
  reset_timeout_ms: 30000
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `failure_threshold` | int | `5` | Consecutive failures before opening the circuit |
| `reset_timeout_ms` | long | `30000` | Milliseconds before attempting a half-open retry |

---

### Health Endpoint

When `KONCERTO_WEB_TYPE=reactive` is set, Koncerto exposes a Spring Boot Actuator health endpoint at `/actuator/health`. The health indicator reports orchestrator state:

- **UP** — orchestrator is running
- **DOWN** — orchestrator has been stopped or failed to start

The endpoint checks the `OrchestratorHealthIndicator` which monitors the orchestrator's running state. No additional configuration is required.

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

---

### CLI Commands

Koncerto accepts the following commands when passed as the first argument:

| Command | Description |
|---------|-------------|
| _(no args)_ | Start the orchestrator poll loop |
| `status` | Print orchestrator state (projects, running/blocked/retrying counts, token totals) |
| `agents` | List running agents per project with turn count and duration |
| `restart` | Clear all runtime state and restart the poll loop |
| `help` | Show available CLI commands |

Example usage:

```bash
java -jar koncerto-app.jar WORKFLOW.md status
java -jar koncerto-app.jar WORKFLOW.md agents
java -jar koncerto-app.jar WORKFLOW.md restart
java -jar koncerto-app.jar WORKFLOW.md help
```

---

### Agent Heartbeat Configuration

Heartbeat monitoring is configured under `agent` per-project. The orchestrator monitors agent subprocess health by tracking periodic heartbeats.

```yaml
agent:
  heartbeat_interval_ms: 30000
  heartbeat_timeout_ms: 90000
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `heartbeat_interval_ms` | long | `30000` | Expected interval between agent heartbeats |
| `heartbeat_timeout_ms` | long | `90000` | Max time without heartbeat before agent is considered stalled |

If an agent fails to send a heartbeat within `heartbeat_timeout_ms`, the orchestrator marks it as stalled and triggers the stall notification (if `on_stalled` is enabled).

---

### Graceful Shutdown

Koncerto supports Spring Boot's graceful shutdown. When a shutdown signal is received (SIGTERM, Ctrl+C), the orchestrator:

1. Sets `shutdownRequested = true`
2. Stops polling for new issues
3. Waits for in-flight agent runs to complete (up to the configured `spring.lifecycle.timeout-per-shutdown-phase`)
4. Cancels remaining agent subprocesses
5. Releases workspace locks

To enable graceful shutdown, add to your `application.yml` or `application.properties`:

```yaml
server:
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

In headless mode, metrics and agent output buffers are flushed before exit.

## License

MIT License. See [LICENSE](LICENSE) for details.
