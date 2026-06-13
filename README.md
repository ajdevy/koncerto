# Koncerto

[![CI](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml/badge.svg)](https://github.com/ajdevy/koncerto/actions/workflows/ci.yml)

A Kotlin/Spring Boot orchestrator that polls Linear for issues, dispatches them to AI agents (opencode, Codex, Claude), and manages the full lifecycle — retries, state transitions, and notifications.

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

## Running

```bash
# Headless
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"

# With dashboard at http://localhost:8080
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

## License

MIT
