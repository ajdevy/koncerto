# Koncerto

[![CI](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml/badge.svg)](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml)
[![code coverage](https://raw.githubusercontent.com/ajdevy/koncerto/main/.badges/jacoco.svg)](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml)

A Kotlin/Spring Boot orchestrator that polls Linear for issues, dispatches them to AI agents (opencode, Codex, Claude), and manages the full lifecycle — retries, state transitions, and notifications.

**Epic handling:** Issues with sub-issues (children) are treated as epics and are skipped by the agent dispatch — only leaf-level tasks are processed. This prevents koncerto from attempting to complete a parent issue that is a container for smaller work items.

## Quick Start

```bash
./gradlew build
export LINEAR_API_KEY=lin_api_xxx
export KONCERTO_WORKSPACE_ROOT=/tmp/workspaces
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"
```

## Configuration

Edit `WORKFLOW.md` (YAML front matter + agent prompt template). See the bundled `WORKFLOW.md` for a full example.

### Projects

```yaml
projects:
  my-project:
    tracker:
      kind: linear
      api_key: $LINEAR_API_KEY       # $VAR references env / local.properties
      project_slug: MYPROJECT
      active_states: [Todo, "In Review"]
      terminal_states: [Done, Cancelled]
    workspace:
      root: $KONCERTO_WORKSPACE_ROOT
    agent:
      kind: opencode                  # opencode, codex, or claude
      max_concurrent_agents: 2
      max_turns: 5
      stages:
        Todo:
          prompt: prompts/implement.md
          on_complete_state: "In Review"
```

### Notifications

```yaml
notifications:
  on_completed: true
  on_failed: true
  on_stalled: true
  on_clarification: true
  on_limit: [telegram, logging]      # limit alerts (rate limits, token quota)
  telegram:
    bot_token: $TELEGRAM_BOT_TOKEN    # $VAR = env var or local.properties
    chat_id: "123456789"
  email:
    smtp_host: smtp.example.com
    username: $SMTP_USERNAME
    password: $SMTP_PASSWORD
    from: koncerto@example.com
    to: team@example.com
  webhook:
    url: https://hooks.example.com/koncerto
```

### Local Secrets

Sensitive values (bot tokens, API keys) can go in `local.properties` (gitignored):

```bash
cp local.properties.example local.properties
# Edit local.properties with your secrets
```

Then reference them in WORKFLOW.md as `$TELEGRAM_BOT_TOKEN`, `$LINEAR_API_KEY`, etc.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LINEAR_API_KEY` | Linear API key | — |
| `KONCERTO_WORKFLOW_PATH` | Path to workflow file | `./WORKFLOW.md` |
| `KONCERTO_LOGS_ROOT` | Log output directory | (stderr) |
| `KONCERTO_WORKSPACE_ROOT` | Agent workspace root dir | `/tmp/koncerto` |
| `KONCERTO_WEB_TYPE` | `reactive` for dashboard, `none` for headless | `none` |
| `KONCERTO_DASHBOARD_PORT` | Dashboard server port | `17348` |
| `NGROK_AUTH_TOKEN` | ngrok tunnel auth token | — |

## Running

### Convenience script

`scripts/koncerto-run.sh` builds the JAR, builds the agent Docker image, then starts the stack with Docker Compose:

```bash
# Build everything and run (attached)
./scripts/koncerto-run.sh

# Dev mode — skip JAR rebuild, use existing JAR
./scripts/koncerto-run.sh --dev

# Run in background and wait for health check
./scripts/koncerto-run.sh --detach

# Set workflow and model via env vars
WORKFLOW_FILE=my-workflow.md KONCERTO_IMPLEMENTATION_MODEL=opencode ./scripts/koncerto-run.sh
```

See `scripts/koncerto-run.sh --help` for all options.

### Manual

```bash
# Headless
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"

# With dashboard at http://localhost:17348
KONCERTO_WEB_TYPE=reactive ./gradlew :koncerto-app:bootRun --args="--port WORKFLOW.md"

# Fat JAR
./gradlew :koncerto-app:bootJar
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md

# CLI commands: status, agents, restart, help
java -jar koncerto-app.jar WORKFLOW.md status
```

## Project Structure

```
koncerto-core/         Issue model, config, Result wrapper
koncerto-logging/      StructuredLogger
koncerto-workflow/     YAML front matter parser, prompt renderer
koncerto-workspace/    Workspace isolation
koncerto-linear/       Linear GraphQL client
koncerto-agent/        JSON-RPC agent subprocess runner
koncerto-orchestrator/ Poll loop, dispatch, retry
koncerto-metrics/      SQLite metrics store
koncerto-dashboard/    HTML dashboard + REST API
koncerto-app/          Spring Boot entry point
koncerto-e2e/          Integration tests
```

## AI Code Review

Review runs as a staged pipeline rather than one opaque model call. Everything that can be
decided deterministically is Kotlin — eligibility, routing, gating, and state transitions — so
the model's job is narrowed to reading code and emitting findings.

```
eligibility → risk routing → context pack → review → publication gate → publish → telemetry
```

Configure it per stage in `WORKFLOW.md` under `review:` (every key is optional; omitting the
block preserves the previous behavior exactly):

```yaml
"In Review":
  command: claude --print --output-format json   # exposes token usage for telemetry
  review:
    mode: blocking            # or `advisory` — publishes findings but never blocks
    skip_globs: [".koncerto/**", "**/*.lock"]   # skipped without a model call
    critical_globs: ["**/auth/**"]              # forces the critical tier
    publication_thresholds:                     # drop findings below this confidence
      critical: 0.5
      warning: 0.7
      suggestion: 0.85
    specialists: []           # e.g. [prompts/review-security.md] for critical-tier fan-out
```

**Domain invariants.** Drop a `review-invariants.md` in the repo root to teach the reviewer
rules it cannot infer from a diff (see this repo's own for an example). It is injected into
the review context.

**Measuring usefulness.** The point is signal-to-cost, not comment count. Every run records
its findings, confidence, tokens, latency, and prompt version:

| Endpoint | Purpose |
|----------|---------|
| `GET /api/v1/review/runs` | Review runs with verdict, tier, tokens |
| `GET /api/v1/review/runs/{runId}/findings` | Findings, including gate-dropped ones |
| `POST /api/v1/review/findings/{id}/label?label=accept\|reject\|false_positive` | Human feedback |
| `GET /api/v1/review/baseline?window=30` | High-evidence rate, FP rate, tokens per useful finding |

```bash
./scripts/review-baseline.sh --stdout       # snapshot the current numbers
./scripts/review-calibration.sh --stdout    # FP analysis + threshold recommendations
```

Roll out measurement-first: collect a baseline (≥30 reviews) before tuning thresholds, and
start new projects in `advisory` mode until the false-positive rate is under 20%. Design notes
and decision records: `_bmad-output/planning-artifacts/architecture-review-quality.md`.

## Docker Agent Isolation

Koncerto can run each agent in a dedicated Docker container for process-level isolation. This is **enabled by default** when Docker is available.

### How It Works

- Each agent gets its own container (`koncerto-agent-<timestamp>-<counter>`)
- The workspace is bind-mounted into the container
- Communication uses `docker exec -i` (same JSON-RPC protocol as subprocess mode)
- Container logs are captured on crash/timeout, then the container is removed
- The agent image is built automatically on koncerto startup

### Configuration

In your workflow YAML under `agent.docker`:

```yaml
agent:
  kind: opencode
  docker:
    enabled: true          # default: true
    image: koncerto-agent:latest
    cpu: "auto"            # auto = available_cpus / max_agents (min 0.5)
    memory: "auto"         # auto = free_memory * 0.8 / max_agents (min 512MB)
    network: true          # attach to koncerto-network bridge
    dockerfile: Dockerfile.agent
```

### Disabling Docker Isolation

Set `agent.docker.enabled: false` to use the existing subprocess mode:

```yaml
agent:
  kind: opencode
  docker:
    enabled: false
```

## Remote Dashboard Access

The dashboard can be exposed via ngrok for secure remote access with OAuth authentication.

### Prerequisites

1. An [ngrok account](https://dashboard.ngrok.com/signup) (free tier works)
2. Your ngrok auth token from https://dashboard.ngrok.com/get-started/your-authtoken

### Setup

```bash
# 1. Copy the example config and customize
cp config/ngrok.example.yml config/ngrok.yml

# 2. Set your auth token
export NGROK_AUTH_TOKEN=your_token_here
```

Edit `config/ngrok.yml` and uncomment your preferred OAuth provider (Google or GitHub), then set your allowed email domains:

```yaml
oauth:
  provider: google
  allow_domains:
    - yourcompany.com
```

### Running

The ngrok tunnel is managed as a Docker sidecar. Start with the dashboard profile:

```bash
docker compose --profile dashboard up -d
```

This starts both `koncerto` (dashboard on port 17348) and `ngrok` (tunnel with OAuth). The ngrok URL is printed to the ngrok container logs:

```bash
docker logs koncerto-ngrok 2>&1 | grep "started tunnel"
```

### Architecture

```
┌──────────────┐     OAuth       ┌──────────────┐
│  ngrok       │ ──────────────> │  Internet    │
│  (sidecar)   │                 │  (Google/GH) │
└──────┬───────┘                 └──────────────┘
       │ http://koncerto:17348
       ▼
┌──────────────┐
│  Koncerto    │
│  Dashboard   │
│  port 17348  │
└──────────────┘
```

### Requirements

- Docker must be accessible via `/var/run/docker.sock`
- The `docker` CLI must be available inside the koncerto container
- At least 512MB free memory per agent container

## License

MIT
