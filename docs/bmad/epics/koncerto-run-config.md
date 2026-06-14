# Epics & Stories: Koncerto Run Configuration

> Implementation order: dependencies first, features after.

## Epic 1: Docker Compose & Runner Script

**Goal:** Single-command build and start of Koncerto.

### Story 1.1: Create docker-compose.yml
**As a** developer
**I want** a docker-compose.yml that defines all services
**So that** the stack starts consistently across environments

**Tasks:**
- Define `koncerto-app` service (build: `./koncerto-app`, port 8080, volumes for config/workflows)
- Define `koncerto-agent` service (build: `Dockerfile.agent`, depends_on `koncerto-app`)
- Create shared network `koncerto-network`
- Add healthcheck to `koncerto-app` (`/actuator/health`)
- **Acceptance:** `docker-compose up -d` starts both services; `docker-compose ps` shows both healthy

### Story 1.2: Create koncerto-run.sh
**As a** developer
**I want** a script that builds everything and starts docker-compose
**So that** I start in one command

**Tasks:**
- Build JAR: `./gradlew :koncerto-app:bootJar -x test`
- Build agent image: `docker build -f Dockerfile.agent -t koncerto-agent:latest .`
- `docker-compose up -d`
- Wait for health: poll `/actuator/health` up to 60s
- `docker-compose logs -f`
- Graceful shutdown on SIGTERM
- Support `--dev` (skip build), `--clean` (rebuild)
- **Acceptance:** `./scripts/koncerto-run.sh` produces a running stack with healthy app

---

## Epic 2: Workflow YAML Multi-Model Configuration

**Goal:** Route tasks to different agents based on labels/states/priorities.

### Story 2.1: Create sample multi-model workflow config
**As a** platform operator
**I want** a workflow markdown file with multi-model routing configured
**So that** tasks route to proper agents

**Tasks:**
- Create `workflows/koncerto-multi-model.md` with frontmatter
- Define `agent.stages: coding → review → simple`
- Define `agent.routing_rules` for feat/* → codex, label:review → claude, state:Todo → opencode
- Define `agent.agents` map with provider configs
- **Acceptance:** Workflow loader parses config without errors; config contains stages, routing_rules, agents maps

### Story 2.2: Wire multi-model config into AgentRunner dispatch
**As a** developer
**I want** the orchestartor to use routing_rules when dispatching tasks
**So that** tasks reach the correct agent

**Tasks:**
- In `Orchestrator.kt` or dispatch flow, apply `routingRules` before creating runtime
- Match rules in priority order; fall back to default `agent.kind`
- **Acceptance:** Issue with label "feat/x" creates CodexRuntime; issue with label "review" creates ClaudeReviewRuntime

---

## Epic 3: Free Model Cycling & Retry

**Goal:** OpenCode free tier cycles through models with retry/backoff/alert.

### Story 3.1: Implement FreeModelCycler
**As a** developer
**I want** a component that cycles through free models with retry tracking
**So that** simple tasks try multiple models before failing

**Tasks:**
- Create `FreeModelCycler.kt` with model list, current index, retry counts map
- `nextModel()` → returns next model or throws `ModelExhaustedException`
- `reportFailure(model)` → increment retry count
- `reset()` → reset to first model
- **Acceptance:** Unit tests confirm: cycle 3 models, 3 retries each, exhaustion throws, reset works

### Story 3.2: Implement ModelRetryHandler
**As a** developer
**I want** retry logic that backs off on failure and alerts on exhaustion
**So that** failures are handled gracefully

**Tasks:**
- Create `ModelRetryHandler.kt` wrapping agent execution
- On failure: exponential backoff (1s, 2s, 4s)
- On exhaustion:
  1. `linearClient.updateIssue(id, "Blocked")`
  2. Write `.model-exhausted` file (JSON: issueId, modelsTried, totalRetries, timestamp, reason)
  3. `compositeNotifier.send()` for alert
  4. `logger.warn("model_exhaustion", ...)`
- **Acceptance:** Unit tests: 3 retries + success on 4th; exhaustion → blocked + status file + notification

### Story 3.3: Wire FreeModelCycler + ModelRetryHandler into OpencodeRuntime
**As a** developer
**I want** opencode agent to use the cycler/retry handler automatically
**So that** all opencode simple tasks benefit from free model cycling

**Tasks:**
- Pass `FreeModelCycler` to `OpencodeRuntime` constructor (optional: only when model="free")
- On `turn/start`, use cycler to select model
- Wrap execution in `ModelRetryHandler`
- **Acceptance:** OpencodeRuntime with free model cycles through models on sequential failures

---

## Epic 4: Automatic Review Gate

**Goal:** Claude Opus 4.8 reviews Codex output automatically.

### Story 4.1: Implement AutoReviewOrchestrator
**As a** developer
**I want** the orchestartor to auto-trigger review after Codex completes a turn
**So that** code is always reviewed before moving to Done

**Tasks:**
- Create `AutoReviewOrchestrator.kt`
- Subscribe to `AgentEvent.TurnCompleted` events; filter to Codex agents
- Lookup `stages.review` from project config
- Build review prompt from changed code + original task description
- Dispatch review to Claude via `AgentRunner`
- Track attempts per issue in `RuntimeState`
- On review pass: update ticket to `onCompleteState`
- On review fail + attempts < maxReviewAttempts: re-route to Codex with review feedback
- On max attempts exhausted: block ticket + status file + notify
- **Acceptance:** Unit tests: happy path (review passes), retry path (review fails, reroutes), block path (max attempts)

### Story 4.2: Wire AutoReviewOrchestrator into Beans
**As a** developer
**I want** the Spring Boot config to wire AutoReviewOrchestrator
**So that** it starts automatically with Koncerto

**Tasks:**
- Add `autoReviewOrchestrator` bean in `Beans.kt`
- Inject `AgentRunner`, `RuntimeState`, `ServiceConfig`, `CompositeNotifier`, `StructuredLogger`
- **Acceptance:** App starts with AutoReviewOrchestrator active; review triggers on Codex TurnCompleted
