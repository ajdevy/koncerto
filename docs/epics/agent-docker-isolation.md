---
title: "Docker Container Agent Isolation - Epics & Stories"
status: "approved"
date: "2026-06-13"
version: "1.0"
prd: "../prd/agent-docker-isolation.md"
architecture: "../architecture/agent-docker-isolation.md"
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - docs/prd/agent-docker-isolation.md
  - docs/architecture/agent-docker-isolation.md
---

# Epics & Stories: Docker Container Agent Isolation

## Overview

Project: Koncerto — Docker container isolation for agent subprocesses

## Requirements Inventory

### Functional Requirements

FR1: Each agent runs in a separate Docker container
FR2: Stdio JSON-RPC communication via docker exec
FR3: Workspace directory bind-mounted into container
FR4: Container removed after completion (logs extracted first)
FR5: Agent Docker image built at koncerto startup
FR6: Dynamic CPU/memory limits based on available resources
FR7: Isolation configurable per project (enabled by default)
FR8: Container logs captured on crash/timeout
FR9: Agent container gets network access
FR10: Host Docker socket mounted into koncerto container

### Non-Functional Requirements

NFR1: Container startup overhead < 1s (image cached)
NFR2: Memory overhead < 20MB per container runtime
NFR3: Max 5 concurrent containers
NFR4: Container cleanup on crash < 10s
NFR5: Backward compatible — existing subprocess path unchanged

### Additional Requirements

- Dockerfile.agent with git, JDK 21, gradle, opencode/codex CLI
- Shell out to `docker` CLI via ProcessBuilder (no new deps)
- Static defaults: 2 CPU, 4GB RAM per container
- Image build on startup with cache check

## FR Coverage Map

FR1: Epic 1 — DockerRuntime container lifecycle
FR2: Epic 1 — docker exec -i via ProcessBuilder
FR3: Epic 1 — Workspace bind mount in docker run
FR4: Epic 1 — DockerRuntime.stop() with log collection
FR5: Epic 1 — Dockerfile.agent + startup build
FR6: Epic 3 — Dynamic resource calculation
FR7: Epic 2 — Config parsing + runtime routing
FR8: Epic 3 — Log collection on crash
FR9: Epic 1 — Container networking config
FR10: Epic 2 — docker-compose socket mount

## Epic List

### Epic 1: Agent Container Image & Runtime
Users can launch agents in Docker containers with isolated environments.
**FRs covered:** FR1, FR2, FR3, FR4, FR5, FR9

### Epic 2: Configuration & Wiring
Users can configure Docker isolation per project and koncerto can be deployed with Docker support.
**FRs covered:** FR7, FR10
**NFRs covered:** NFR5

### Epic 3: Resource Management & Observability
Agents get appropriate resources and failures are observable.
**FRs covered:** FR6, FR8
**NFRs covered:** NFR1, NFR2, NFR3, NFR4

### Epic 4: Testing & Hardening
The feature is tested, handles errors gracefully, and is documented.
**NFRs covered:** NFR5

---

## Epic 1: Agent Container Image & Runtime

Goal: Build the Docker agent image and DockerRuntime class for full container lifecycle management.

### Story 1.1: Create Dockerfile.agent

As a koncerto operator,
I want a Docker image with opencode/codex CLI and standard dev tools,
So that agents have everything they need to code inside the container.

**Acceptance Criteria:**

**Given** the project root,
**When** `docker build -f Dockerfile.agent -t koncerto-agent:latest .` is run,
**Then** the image builds successfully
**And** contains: opencode CLI (or codex), git, JDK 21, gradle
**And** the image is under 1GB
**And** WORKDIR is `/workspace`

### Story 1.2: Implement DockerRuntime.start()

As a koncerto developer,
I want DockerRuntime to create and start an agent container,
So that agents run in isolated environments.

**Acceptance Criteria:**

**Given** a DockerRuntime with a workspace path and DockerConfig,
**When** `start()` is called,
**Then** a Docker container is created via `docker run -d --name <id> -v <workspace>:/workspace -w /workspace koncerto-agent:latest sleep infinity`
**And** the container ID is stored
**And** `isAlive()` returns true
**And** the workspace directory is bind-mounted into the container

### Story 1.3: Implement docker exec communication

As a koncerto developer,
I want DockerRuntime to communicate with the agent via `docker exec -i`,
So that existing JSON-RPC framing code works unchanged.

**Acceptance Criteria:**

**Given** a running container,
**When** commands are sent via `docker exec -i <container> bash -lc '<command>'`,
**Then** stdout is read and parsed for JSON-RPC messages via existing dispatchMessage()
**And** stderr is collected separately
**And** the existing AgentRuntime interface (`send()`, `output`, `events()`) works identically to StdioAgentRuntime

### Story 1.4: Implement DockerRuntime.stop()

As a koncerto developer,
I want containers to be cleaned up after agent completion,
So that no orphaned containers remain.

**Acceptance Criteria:**

**Given** a running or stopped container,
**When** `stop()` is called,
**Then** `docker logs <container>` is captured (if available)
**And** `docker rm -f <container>` is executed
**And** the container is confirmed removed
**And** no orphaned containers remain after `stop()`

### Story 1.5: Container networking

As a koncerto operator,
I want agent containers to have network access,
So that agents can clone repos and install dependencies.

**Acceptance Criteria:**

**Given** DockerConfig.network = true,
**When** the container starts,
**Then** it is attached to the koncerto bridge network
**And** the agent has outbound network access

---

## Epic 2: Configuration & Wiring

Goal: Config parsing, runtime factory routing, and deployment changes.

### Story 2.1: Add DockerConfig and config parsing

As a koncerto operator,
I want to configure Docker isolation per project in the workflow YAML,
So that I can enable/disable it per project.

**Acceptance Criteria:**

**Given** a workflow YAML with `agent.docker.enabled: true`,
**When** config is parsed,
**Then** a `DockerConfig` object is created with:
  - `enabled: Boolean = true`
  - `image: String = "koncerto-agent:latest"`
  - `cpu: String = "auto"`
  - `memory: String = "auto"`
  - `network: Boolean = true`
  - `dockerfile: String = "Dockerfile.agent"`
**Given** `agent.docker.enabled: false`,
**When** config is parsed,
**Then** the existing subprocess path is used

### Story 2.2: Wire DockerRuntime into AgentRuntimeFactory

As a koncerto developer,
I want DockerRuntime to be selected based on config,
So that no manual code changes are needed to use containers.

**Acceptance Criteria:**

**Given** DockerConfig.enabled = true,
**When** AgentRuntimeFactory.create() is called,
**Then** a DockerRuntime is returned
**Given** DockerConfig.enabled = false,
**When** AgentRuntimeFactory.create() is called,
**Then** the existing runtime (OpencodeRuntime/CodexRuntime) is returned
**And** all existing projects without docker config continue using subprocess mode

### Story 2.3: Update docker-compose.yml

As a koncerto operator,
I want the koncerto container to have access to the Docker daemon,
So that it can manage agent containers.

**Acceptance Criteria:**

**Given** docker-compose.yml,
**When** inspected,
**Then** it has a volume mount: `/var/run/docker.sock:/var/run/docker.sock`
**And** the koncerto container can create sibling containers
**And** an environment variable `KONCERTO_DOCKER_ENABLED=true` is set

### Story 2.4: Image build on startup

As a koncerto operator,
I want the agent image to be built automatically when koncerto starts,
So that no manual build steps are needed.

**Acceptance Criteria:**

**Given** koncerto starts with Docker enabled,
**When** the application boots,
**Then** `docker build -f Dockerfile.agent -t koncerto-agent:latest .` is run
**And** if the image already exists, it is skipped (cache hit)
**And** if the build fails, a warning is logged and koncerto falls back to subprocess mode

---

## Epic 3: Resource Management & Observability

Goal: Dynamic resource allocation, log collection, health monitoring.

### Story 3.1: Dynamic resource calculation

As a koncerto operator,
I want CPU/memory limits to be set automatically on agent containers,
So that agents don't starve each other.

**Acceptance Criteria:**

**Given** `agent.docker.cpu: "auto"`,
**When** a container starts,
**Then** CPU limit = `availableProcessors / maxConcurrentAgents` (minimum 0.5)
**And** the `--cpus` flag is set on `docker run`
**Given** `agent.docker.memory: "auto"`,
**When** a container starts,
**Then** memory limit = `availableOSMemory * 0.8 / maxConcurrentAgents`
**And** the `--memory` flag is set on `docker run`
**Given** explicit values like `cpu: "2.0"` or `memory: "4g"`,
**When** a container starts,
**Then** those exact values are used

### Story 3.2: Container health monitoring

As a koncerto developer,
I want the runtime to detect when a container crashes,
So that koncerto can handle failures appropriately.

**Acceptance Criteria:**

**Given** a running container,
**When** `isAlive()` is called,
**Then** it checks `docker inspect <container> --format='{{.State.Status}}'`
**And** returns true only if status is "running"
**Given** a container that has crashed,
**When** the alive check runs in AgentRunner's heartbeat loop,
**Then** it throws `IllegalStateException("agent_process_died")` within `heartbeatIntervalMs`

### Story 3.3: Log collection on crash

As a koncerto operator,
When an agent container crashes,
I want the logs captured before container removal,
So that I can debug what went wrong.

**Acceptance Criteria:**

**Given** a container that has crashed or timed out,
**When** `stop()` is called,
**Then** `docker logs <container>` is executed and output is emitted to koncerto's logging system
**And** the log output is tagged with the issue identifier
**And** the container is then force-removed

---

## Epic 4: Testing & Hardening

Goal: Tests, error handling, edge cases, documentation.

### Story 4.1: Unit tests for DockerRuntime

As a koncerto developer,
I want DockerRuntime to have unit test coverage,
So that regressions are caught.

**Acceptance Criteria:**

**Given** DockerRuntime implementation,
**When** tests are run,
**Then** tests cover:
  - `start()` success and failure paths
  - `stop()` with log collection
  - `isAlive()` with running and stopped containers
  - `send()` writes to docker exec stdin
  - edge cases: Docker daemon unavailable, container already exists

### Story 4.2: Config parsing tests

As a koncerto developer,
I want DockerConfig parsing to be tested,
So that config errors are caught at parse time.

**Acceptance Criteria:**

**Given** ServiceConfig parsing,
**When** tests are run,
**Then** tests cover:
  - docker section fully specified
  - docker section omitted (default = enabled)
  - docker.enabled: false
  - auto vs explicit cpu/memory values
  - invalid values produce clear errors

### Story 4.3: Error handling

As a koncerto operator,
I want graceful handling of Docker failures,
So that koncerto doesn't crash when Docker is unavailable.

**Acceptance Criteria:**

**Given** Docker daemon is not running,
**When** koncerto starts,
**Then** a warning is logged
**And** koncerto falls back to subprocess mode
**Given** a container fails to start,
**When** `start()` is called,
**Then** an error event is emitted via `AgentEvent.StartupFailed`
**And** the agent runner handles it with the existing retry logic
**Given** a container fails to be removed,
**When** `stop()` is called,
**Then** the error is logged but does not crash koncerto

### Story 4.4: Documentation

As a koncerto operator,
I want to understand how Docker isolation works,
So that I can configure and troubleshoot it.

**Acceptance Criteria:**

**Given** the koncerto README,
**When** read,
**Then** it documents:
  - How Docker isolation works
  - Configuration options (`agent.docker.*`)
  - How to disable it
  - Docker socket requirements
  - Resource allocation behavior
