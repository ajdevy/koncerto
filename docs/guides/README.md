# Koncerto — User Guide

**Version:** 1.0  
**Date:** 2026-06-08  

---

## Table of Contents

1. [Introduction](#introduction)
2. [Prerequisites](#prerequisites)
3. [Installation](#installation)
4. [Quick Start](#quick-start)
5. [Configuration](#configuration)
6. [Running Koncerto](#running-koncerto)
7. [Dashboard & Monitoring](#dashboard--monitoring)
8. [Troubleshooting](#troubleshooting)
9. [Examples](#examples)
10. [FAQ](#faq)

---

## Introduction

Koncerto is an orchestration service that automates software development workflows by connecting project trackers (Linear) with AI coding agents (Codex). It automatically picks up issues, dispatches them to isolated workspaces, and manages the agent lifecycle.

### Key Features

- **Automatic Issue Discovery:** Polls Linear for new issues in active states
- **Isolated Workspaces:** Each issue gets its own directory with shell hooks
- **Agent Management:** Spawns Codex subprocesses via JSON-RPC
- **Retry Logic:** Exponential backoff for transient failures
- **Live Dashboard:** Real-time visibility into agent activity

---

## Prerequisites

Before using Koncerto, ensure you have:

| Requirement | Version | Purpose |
|-------------|---------|---------|
| Java JDK | 21+ | Runtime |
| Gradle | 8.10+ | Build (wrapper included) |
| Codex CLI | Latest | AI agent runtime |
| Linear Account | — | Project tracker |

### Verify Prerequisites

```bash
# Check Java version
java -version
# Expected: openjdk version "21.0.x" or higher

# Check Codex is installed
codex --version
# Expected: codex version x.x.x

# Check Linear API key
echo $LINEAR_API_KEY
# Expected: your API key
```

---

## Installation

### From Source

```bash
# Clone the repository
git clone https://github.com/your-org/koncerto.git
cd koncerto

# Build all modules
./gradlew build

# Run tests (optional)
./gradlew test

# Create executable JAR
./gradlew :koncerto-app:bootJar
```

### Verify Installation

```bash
# Check the JAR was created
ls -la koncerto-app/build/libs/koncerto-app-*.jar

# Run with --help (if implemented)
java -jar koncerto-app/build/libs/koncerto-app-*.jar --help
```

---

## Quick Start

### 1. Set Environment Variables

```bash
export LINEAR_API_KEY="your-linear-api-key"
export KONCERTO_WORKFLOW_PATH="./WORKFLOW.md"
```

### 2. Create Workflow File

Create a `WORKFLOW.md` in your project root:

```markdown
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
agent:
  max_concurrent_agents: 3
  max_turns: 10
---

You are working on Linear issue {{ issue.identifier }}.

Title: {{ issue.title }}
Description: {{ issue.description }}

Please implement this feature following the project's coding standards.
```

### 3. Run Koncerto

```bash
# Option 1: Using Gradle
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"

# Option 2: Using JAR
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md
```

### 4. Verify It's Working

```bash
# Check the dashboard
open http://localhost:8080

# Check the API
curl http://localhost:8080/api/v1/state | jq
```

---

## Configuration

### Workflow File Format

Koncerto reads configuration from a `WORKFLOW.md` file with YAML front matter:

```markdown
---
# YAML configuration here
---

Prompt template with Liquid variables here.
```

### Configuration Options

#### Tracker Settings

```yaml
tracker:
  kind: linear                    # Tracker type (only "linear" supported)
  api_key: $LINEAR_API_KEY        # API key (env var reference)
  project_slug: MYPROJECT         # Linear project slug
  active_states:                  # States to poll for
    - Todo
    - In Progress
    - Backlog
  terminal_states:                # States that indicate completion
    - Done
    - Cancelled
    - Won't Fix
  required_labels:                # Optional: only process issues with these labels
    - bug
    - enhancement
```

#### Polling Settings

```yaml
polling:
  interval_ms: 30000              # Poll interval in milliseconds
```

#### Workspace Settings

```yaml
workspace:
  root: /tmp/koncerto_workspaces  # Root directory for workspaces
```

#### Hook Settings

```yaml
hooks:
  after_create: echo "workspace created"  # After workspace creation
  before_run: npm install                 # Before agent runs
  after_run: npm test                     # After agent completes
  before_remove: echo "cleaning up"       # Before workspace removal
  timeout_ms: 60000                       # Hook timeout in ms
```

#### Agent Settings

```yaml
agent:
  max_concurrent_agents: 5       # Max simultaneous agents
  max_turns: 20                  # Max turns per issue
  max_retry_backoff_ms: 300000   # Max retry delay (5 minutes)
  max_concurrent_agents_by_state:  # Optional: per-state limits
    Todo: 3
    InProgress: 5
```

#### Codex Settings

```yaml
codex:
  command: codex app-server      # Codex command
  turn_timeout_ms: 3600000       # Turn timeout (1 hour)
  stall_timeout_ms: 300000       # Stall timeout (5 minutes)
  approval_policy:               # Optional: approval settings
    required: false
  thread_sandbox:                # Optional: sandbox settings
    enabled: true
  turn_sandbox_policy:           # Optional: turn sandbox
    enabled: true
```

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LINEAR_API_KEY` | Linear API key | — |
| `KONCERTO_WORKFLOW_PATH` | Path to workflow file | `./WORKFLOW.md` |
| `KONCERTO_LOGS_ROOT` | Log directory | (stderr only) |
| `KONCERTO_WORKSPACE_ROOT` | Workspace root | `/tmp/symphony_workspaces` |
| `KONCERTO_WEB_TYPE` | Web mode (`reactive` or `none`) | `none` |

### Prompt Templates

Koncerto uses Liquid templating for prompts. Available variables:

```liquid
{{ issue.id }}              # Linear issue ID
{{ issue.identifier }}      # Human-readable ID (e.g., PROJ-42)
{{ issue.title }}           # Issue title
{{ issue.description }}     # Issue description
{{ issue.priority }}        # Priority (1-4)
{{ issue.state }}           # Current state
{{ issue.labels }}          # Array of labels
{{ issue.blockedBy }}       # Array of blocker references
{{ issue.url }}             # Link to issue in Linear
```

Example prompt:

```markdown
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: MYPROJECT
  active_states: [Todo]
  terminal_states: [Done]
---

You are working on {{ issue.identifier }}.

## Issue Details

**Title:** {{ issue.title }}
**Priority:** {{ issue.priority }}
**Labels:** {{ issue.labels | join: ", " }}

## Description

{{ issue.description }}

## Instructions

1. Implement the feature described above
2. Write tests for your changes
3. Update documentation if needed
4. Create a PR with a descriptive title

## Requirements

- Follow the project's coding standards
- Ensure all tests pass
- Add appropriate comments
- Update CHANGELOG if significant
```

---

## Running Koncerto

### Development Mode

```bash
# Run with Gradle (auto-reload)
./gradlew :koncerto-app:bootRun --args="WORKFLOW.md"

# Run with specific port
./gradlew :koncerto-app:bootRun --args="--port 8080 WORKFLOW.md"
```

### Production Mode

```bash
# Build JAR
./gradlew :koncerto-app:bootJar

# Run JAR
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md

# Run with custom config
java -Dkoncerto.workflow.path=/path/to/WORKFLOW.md \
     -Dkoncerto.workspace.root=/data/workspaces \
     -jar koncerto-app/build/libs/koncerto-app-*.jar
```

### Headless Mode (No Dashboard)

```bash
# Disable web interface
export KONCERTO_WEB_TYPE=none

# Run
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md
```

### Background Mode

```bash
# Run in background with nohup
nohup java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md \
    > /var/log/koncerto.log 2>&1 &

# Or use systemd (Linux)
sudo systemctl start koncerto
```

---

## Dashboard & Monitoring

### Accessing the Dashboard

```bash
# Open in browser
open http://localhost:8080

# Or with custom port
open http://localhost:9090
```

### Dashboard Features

The dashboard provides:

- **Running Issues:** Currently executing issues with token usage
- **Retrying Issues:** Issues scheduled for retry with attempt counts
- **Metrics:** Total tokens, seconds running, active slots
- **Auto-Refresh:** Updates every 5 seconds

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/` | GET | HTML dashboard |
| `/api/v1/state` | GET | JSON state snapshot |
| `/api/v1/{identifier}` | GET | Issue details |
| `/api/v1/refresh` | POST | Manual refresh |

### Monitoring with curl

```bash
# Get current state
curl -s http://localhost:8080/api/v1/state | jq

# Watch running issues
watch -n 5 'curl -s http://localhost:8080/api/v1/state | jq ".running | length"'

# Get specific issue
curl -s http://localhost:8080/api/v1/PROJ-42 | jq

# Trigger refresh
curl -X POST http://localhost:8080/api/v1/refresh
```

### Integrating with Monitoring Systems

#### Prometheus

```python
# prometheus_exporter.py
from flask import Flask, Response
import requests

app = Flask(__name__)

@app.route('/metrics')
def metrics():
    state = requests.get('http://localhost:8080/api/v1/state').json()
    
    metrics = []
    metrics.append(f'koncerto_running_count {len(state["running"])}')
    metrics.append(f'koncerto_retrying_count {len(state["retrying"])}')
    metrics.append(f'koncerto_total_tokens {state["codexTotals"]["totalTokens"]}')
    metrics.append(f'koncerto_seconds_running {state["codexTotals"]["secondsRunning"]}')
    
    return Response('\n'.join(metrics), mimetype='text/plain')
```

#### Grafana

Use the Prometheus exporter above and create a Grafana dashboard with:

- Running issues gauge
- Retry count gauge
- Token usage counter
- Uptime timer

---

## Troubleshooting

### Common Issues

#### 1. "Missing API Key" Error

```
Error: missing_tracker_api_key
```

**Solution:** Set the `LINEAR_API_KEY` environment variable:

```bash
export LINEAR_API_KEY="your-api-key"
```

#### 2. "Project Not Found" Error

```
Error: linear_api_status: 404
```

**Solution:** Verify the project slug exists in Linear:

```bash
# Check project slug
curl -H "Authorization: $LINEAR_API_KEY" \
     https://api.linear.app/graphql \
     -d '{"query":"{ projects { nodes { slug } } }"}'
```

#### 3. Agent Hangs

**Symptom:** Issue stuck in "running" state

**Solution:**

1. Check agent logs
2. Verify Codex is responding
3. Increase `stall_timeout_ms`
4. Restart Koncerto

#### 4. Workspace Creation Fails

```
Error: Failed to create workspace
```

**Solution:**

1. Check disk space
2. Verify `workspace.root` directory exists
3. Check permissions

#### 5. No Issues Being Processed

**Symptom:** Dashboard shows no running issues

**Solution:**

1. Verify Linear API key is valid
2. Check project slug is correct
3. Ensure issues are in active states
4. Check `required_labels` filter

### Debug Mode

Enable debug logging:

```bash
# Add debug logging
export KONCERTO_LOG_LEVEL=debug

# Or modify logging in code
StructuredLogger(listOf(StderrSink()), level = LogLevel.DEBUG)
```

### Logs

Logs are written to stderr by default. To capture logs:

```bash
# Redirect to file
java -jar koncerto-app/build/libs/koncerto-app-*.jar WORKFLOW.md 2> koncerto.log

# Or configure file sink in code
val logger = StructuredLogger(listOf(
    StderrSink(),
    FileSink(Path.of("/var/log/koncerto"))
))
```

---

## Examples

### Example 1: Basic Bug Fix Workflow

```markdown
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: MYAPP
  active_states: [Todo]
  terminal_states: [Done]
polling:
  interval_ms: 60000
workspace:
  root: /tmp/koncerto_bugs
agent:
  max_concurrent_agents: 2
  max_turns: 10
---

You are fixing a bug in our application.

## Bug Details

**ID:** {{ issue.identifier }}
**Title:** {{ issue.title }}

{{ issue.description }}

## Instructions

1. Reproduce the bug
2. Identify the root cause
3. Implement a fix
4. Add regression tests
5. Ensure all tests pass
6. Create a PR with "Fix: {{ issue.title }}"
```

### Example 2: Feature Development

```markdown
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: MYAPP
  active_states: [In Progress]
  terminal_states: [Done, Cancelled]
hooks:
  after_create: |
    cd $WORKSPACE
    git checkout -b feature/{{ issue.identifier | lowercase }}
  after_run: |
    cd $WORKSPACE
    npm test
agent:
  max_concurrent_agents: 3
  max_turns: 20
---

You are implementing a new feature.

## Feature Details

**ID:** {{ issue.identifier }}
**Title:** {{ issue.title }}

{{ issue.description }}

## Requirements

1. Follow the feature specification
2. Write comprehensive tests
3. Update documentation
4. Create a PR with "Feature: {{ issue.title }}"
5. Add to CHANGELOG
```

### Example 3: Refactoring

```markdown
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: MYAPP
  active_states: [Backlog]
  terminal_states: [Done]
required_labels:
  - refactor
agent:
  max_concurrent_agents: 1
  max_turns: 15
hooks:
  before_run: |
    cd $WORKSPACE
    npm run lint
---

You are refactoring code to improve quality.

## Refactoring Task

**ID:** {{ issue.identifier }}
**Title:** {{ issue.title }}

{{ issue.description }}

## Guidelines

1. Maintain existing functionality
2. Improve code readability
3. Reduce complexity
4. Add tests for refactored code
5. Ensure no performance regression
```

---

## FAQ

### Q: How many issues can Koncerto handle simultaneously?

A: By default, 10 concurrent agents. Configure with `agent.max_concurrent_agents`. You can also set per-state limits with `agent.max_concurrent_agents_by_state`.

### Q: What happens if an agent fails?

A: Koncerto schedules a retry with exponential backoff (10s, 20s, 40s, ...) up to `agent.max_retry_backoff_ms`. After max retries, the issue remains in the retry queue until manually resolved.

### Q: Can I use Koncerto with GitHub Issues?

A: Not yet. Currently only Linear is supported. GitHub support is planned for v2.

### Q: How does Koncerto isolate workspaces?

A: Each issue gets its own directory under `workspace.root`. The agent runs in this directory and cannot access other workspaces.

### Q: Can I customize the agent prompt?

A: Yes, edit the content below the YAML front matter in `WORKFLOW.md`. Use Liquid variables like `{{ issue.title }}`.

### Q: How do I stop Koncerto gracefully?

A: Press `Ctrl+C` or send `SIGTERM`. Koncerto will finish current agents and exit.

### Q: Can I run multiple Koncerto instances?

A: Yes, but ensure they use different `workspace.root` directories and Linear projects.

### Q: How do I update Koncerto?

A: Pull the latest code and rebuild:

```bash
git pull
./gradlew clean build
```

---

## Support

- **Documentation:** See `/docs` directory
- **Issues:** Report bugs on GitHub
- **Discussions:** Join the community on GitHub Discussions

---

## License

Koncerto is licensed under the MIT License. See [LICENSE](../LICENSE) for details.
