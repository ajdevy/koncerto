---
title: "Docker Container Agent Isolation - Architecture"
status: "approved"
date: "2026-06-13"
version: "1.0"
prd: "../prd/agent-docker-isolation.md"
stepsCompleted: [1, 2, 3, 4]
---

# Architecture: Docker Container Agent Isolation

## 1. Architecture Overview

Each agent runs in its own Docker container. The koncerto host mounts `/var/run/docker.sock` to manage sibling containers. Communication uses `docker exec -i` via `ProcessBuilder`, preserving the existing JSON-RPC stdio protocol unchanged.

```
┌─────────────────────────────────────┐
│         koncerto container           │
│  ┌──────────────────────────────┐   │
│  │          JVM                  │   │
│  │  ┌───────────────────────┐   │   │
│  │  │ AgentRuntimeFactory    │   │   │
│  │  │  ├─ StdioRuntime      │   │   │
│  │  │  └─ DockerRuntime     │   │   │
│  │  └──────────┬────────────┘   │   │
│  │             │ docker exec -i  │   │
│  └─────────────┼────────────────┘   │
│               /var/run/docker.sock  │
└────────────────┼────────────────────┘
                 │
          ┌──────┴──────┐
          │ Host Docker  │
          │ Daemon       │
          ├────┬────┬────┤
          │ A1 │ A2 │ A3 │  Agent containers
          └────┴────┴────┘
```

## 2. Core Architectural Decisions

### Decision 1: Docker API Client
**Choice:** Shell out to `docker` CLI via `ProcessBuilder`
**Rationale:** Zero new dependencies, stdout/stderr streams naturally compatible with existing `StdioAgentRuntime` infrastructure. The `docker` CLI is always present when Docker is installed.
**Affects:** `koncerto-agent` module

### Decision 2: Runtime Architecture
**Choice:** New `DockerRuntime` class implementing `AgentRuntime`
**Rationale:** Clean separation from existing subprocess path. `AgentRuntimeFactory.create()` routes to `DockerRuntime` when `agent.docker.enabled = true`. Existing `StdioAgentRuntime`, `CodexRuntime`, `ClaudeReviewRuntime` remain untouched.
**Affects:** `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/`

### Decision 3: Resource Allocation
**Choice:** Static defaults with optional config override
- Default: 2 CPU, 4GB RAM per container
- Config override: `agent.docker.cpu`, `agent.docker.memory`
- `auto` mode: calculated as `available_cpus / max_agents`, `free_mem * 0.8 / max_agents`
**Affects:** `DockerRuntime`, config parsing

### Decision 4: Image Build Timing
**Choice:** Build at koncerto startup; skip if image exists (cache check)
**Rationale:** Docker build cache makes rebuilds fast. No extra build step for users.
**Affects:** `DockerRuntime` init, `Dockerfile.agent`

### Decision 5: Stdio Plumbing
**Choice:** `docker exec -i <container> bash -lc <command>` via `ProcessBuilder`
**Rationale:** Reuses all existing `StdioAgentRuntime` JSON-RPC framing (`JsonRpcFraming`, `dispatchMessage`, etc.) without any modification. The `ProcessBuilder` interface is identical.
**Affects:** No changes to JSON-RPC layer

### Decision 6: Config Model
**Choice:** Nested `agent.docker.*` section in `ProjectConfig`
```yaml
agent:
  docker:
    enabled: true
    image: "koncerto-agent:latest"
    cpu: "auto"
    memory: "auto"
    network: true
```
**Affects:** `ServiceConfig.kt`, `ProjectConfig.kt`, `AgentProjectConfig`

### Decision 7: Log Collection
**Choice:** Stdout/stderr streaming through koncerto's existing output collector; on crash, `docker logs <container>` via CLI before `docker rm`
**Affects:** `DockerRuntime.stop()`, `AgentRunner` error handling

## 3. Implementation Patterns

### Pattern 1: Runtime Selection
```kotlin
// In AgentRuntimeFactory
fun create(agentKind: String, command: String, workspacePath: Path, dockerConfig: DockerConfig?): AgentRuntime {
    val useDocker = dockerConfig?.enabled != false
    return if (useDocker) {
        DockerRuntime(command, workspacePath, logger, dockerConfig!!)
    } else {
        when (agentKind.lowercase()) {
            "codex" -> CodexRuntime(command, workspacePath, logger)
            "opencode" -> OpencodeRuntime(command, workspacePath, logger)
            "claude" -> ClaudeReviewRuntime(command, workspacePath, logger)
        }
    }
}
```

### Pattern 2: Container Lifecycle
1. `start()` → `docker run -d --name <id> --cpus <n> --memory <m> -v <workspace>:<workspace> <image>`
2. Communication → `docker exec -i <id> bash -lc <command>` (wrapped in ProcessBuilder)
3. `stop()` → `docker logs <id>` (capture), then `docker rm -f <id>`

### Pattern 3: Dynamic Resource Calculation
```kotlin
fun calculateResources(maxAgents: Int, config: DockerConfig): Pair<Double, String> {
    val cpus = Runtime.getRuntime().availableProcessors().toDouble() / maxAgents
    val mem = (osFreeMem() * 0.8 / maxAgents).toLong()
    return cpus.coerceAtLeast(0.5) to "${mem}m"
}
```

## 4. Project Structure Changes

```
koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/
├── AgentRuntime.kt           # unchanged
├── AgentRunner.kt            # minor: pass DockerConfig through
├── StdioAgentRuntime.kt      # unchanged
├── OpencodeRuntime.kt        # unchanged
├── CodexAppServerClient.kt   # unchanged
├── ClaudeReviewRuntime.kt    # unchanged
├── DockerRuntime.kt          # NEW: container lifecycle + docker exec
└── Dockerfile.agent          # NEW: agent container image definition

koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/
├── ServiceConfig.kt          # enhanced: parse docker config
├── ProjectConfig.kt          # enhanced: DockerConfig data class
└── DockerConfig.kt           # NEW: data class for docker settings

docker-compose.yml            # enhanced: add docker socket volume mount
```

## 5. Module Dependency Map

```
koncerto-agent
  └── koncerto-core (DockerConfig)
  └── koncerto-logging
  └── koncerto-workspace
  └── Docker (CLI on host)

No new Gradle dependencies required.
```
