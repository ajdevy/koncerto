---
title: "Docker Container Agent Isolation"
status: "draft"
date: "2026-06-13"
version: "1.0"
---

# PRD: Docker Container Agent Isolation

## 1. Executive Summary

Currently, koncerto launches each AI coding agent as a raw OS subprocess (`bash -lc <command>`). While functional, this provides no filesystem, environment, or resource isolation between agents. This PRD proposes wrapping each agent in a lightweight Docker container, providing process-level isolation with minimal overhead while maintaining the existing JSON-RPC communication protocol.

## 2. Problem Statement

| Problem | Impact |
|---------|--------|
| Agents share filesystem — one agent can read/write another's workspace | Data leakage, cross-contamination |
| Agents share environment variables | Secrets exposure between agents |
| No resource limits per agent | One runaway agent can starve others |
| No cleanup guarantees | Orphaned processes, zombie agents |
| Agents share host network | No network policy enforcement |

## 3. Goals & Non-Goals

### Goals
- Each agent runs in its own Docker container with an isolated filesystem, process tree, and configurable resource limits
- Communication remains stdio-based (`docker exec`) — no protocol changes
- Workspace directories are bind-mounted from host into containers
- Dynamic resource allocation (CPU/memory limits based on available host resources)
- Container logs are captured on crash/timeout for debugging, then container is removed
- Isolation is **enabled by default**, configurable per project via `agent.docker.enabled: false`
- Agent Docker image is built at koncerto startup with a standard dev environment baked in

### Non-Goals
- Not replacing the existing subprocess path — both modes coexist
- Not adding Kubernetes or container orchestration (Docker-only)
- Not adding per-agent network isolation policies (containers share bridge network)
- Not handling multi-host or distributed agent execution

## 4. User Personas

| Persona | Needs |
|---------|-------|
| **Solo developer** running koncerto locally | Isolation without complexity. Should work out of the box. |
| **Team** running on a shared server | Prevent agent cross-contamination, resource hogging |
| **CI/CD pipeline** | Deterministic cleanup, predictable resource usage |

## 5. Requirements

### 5.1 Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR1 | Each agent runs in a separate Docker container | P0 |
| FR2 | Stdio JSON-RPC communication via `docker exec` | P0 |
| FR3 | Workspace directory bind-mounted into container as working directory | P0 |
| FR4 | Agent container is removed after completion (logs extracted first) | P0 |
| FR5 | Agent Docker image built at koncerto startup with git, JDK, gradle, opencode/codex CLI | P0 |
| FR6 | Dynamic CPU/memory limits based on available host resources | P1 |
| FR7 | Isolation can be disabled per project via `agent.docker.enabled: false` | P0 |
| FR8 | Container logs captured to koncerto logging system on crash/timeout | P1 |
| FR9 | Agent container gets network access (bridge network) | P1 |
| FR10 | Host Docker socket mounted into koncerto container for container management | P0 |

### 5.2 Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | Startup overhead per container | < 1s (image cached) |
| NFR2 | Memory overhead per container | < 20MB (container runtime) |
| NFR3 | Max concurrent containers | 5 (configurable) |
| NFR4 | Container cleanup on crash | < 10s |
| NFR5 | Backward compatibility | Existing subprocess path unchanged |

## 6. High-Level Design

```
┌─────────────────────────────────────┐
│         koncerto container           │
│                                      │
│  ┌──────────────────────────────┐   │
│  │     Orchestrator / JVM        │   │
│  │                               │   │
│  │  ┌─────────┐  ┌─────────┐   │   │
│  │  │Agent    │  │Agent    │   │   │
│  │  │Runtime  │  │Runtime  │   │   │
│  │  │(Docker) │  │(Docker) │   │   │
│  │  └────┬────┘  └────┬────┘   │   │
│  └───────┼─────────────┼───────┘   │
│          │             │            │
│  ┌───────┴─────────────┴───────┐   │
│  │     /var/run/docker.sock     │   │
│  └───────────┬─────────────────┘   │
└──────────────┼─────────────────────┘
               │
     ┌─────────┴──────────┐
     │   Host Docker       │
     │   Daemon            │
     └──┬──────────────┬──┘
        │              │
  ┌─────┴─────┐  ┌─────┴─────┐
  │ Agent     │  │ Agent     │
  │ Container │  │ Container │
  │ (opencode)│  │ (codex)   │
  │           │  │           │
  │ Bind      │  │ Bind      │
  │ mount:    │  │ mount:    │
  │ workspace │  │ workspace │
  └───────────┘  └───────────┘
```

## 7. Config Changes

### `agent.docker` section (optional, per project)

```yaml
agent:
  docker:
    enabled: true                    # default: true
    image: "koncerto-agent:latest"   # auto-built at startup
    memory: "auto"                   # "auto" = dynamic, or "2g", "4g" etc
    cpu: "auto"                      # "auto" = dynamic, or "1.0", "2.0" etc
    network: true                    # attach to koncerto-network
    dockerfile: "Dockerfile.agent"   # path to agent Dockerfile
```

### Minimal config to disable:
```yaml
agent:
  docker:
    enabled: false
```

## 8. Success Metrics

| Metric | Target |
|--------|--------|
| Agent container starts successfully | 100% |
| Agent isolation prevents workspace cross-contamination | Confirmed by test |
| Container cleanup on agent completion | 100% |
| Logs captured on container crash | 100% |
| No regression in existing subprocess path | All existing tests pass |

## 9. Open Questions

1. Should the agent Docker image be rebuilt on every koncerto restart, or only when the Dockerfile changes?
2. Should we expose container stdout/stderr streams directly, or batch-collect them?
3. What Docker API client library for Kotlin? (docker-java, or shell out to `docker` CLI)
4. How to handle Docker daemon unavailability? (fall back to subprocess, or hard fail?)
