# PRD: Koncerto Run Configuration with Multi-Model Orchestration

## 1. Problem Statement

Koncierto currently lacks a simple, reproducible way to start the full orchestration stack with proper multi-model agent routing. Users need:
- A single command to build and run the entire system (app + agents + dependencies)
- Automatic routing: Codex for coding tasks, Claude Opus 4.8 for code review, OpenCode free tier for simple operations
- Free model cycling with retry/backoff (max 3 retries per model) → block ticket + alert on exhaustion
- Automatic review gate after coding tasks complete

## 2. User Stories

### US-1: One-Command Startup
**As a** developer  
**I want** a single script to build and start Koncerto with all dependencies  
**So that** I can begin orchestrating tasks immediately without manual setup

**Acceptance Criteria:**
- `./scripts/koncerto-run.sh` builds JAR, builds agent Docker image, starts docker-compose
- Health checks pass before declaring "ready"
- Logs show clear startup status

### US-2: Multi-Model Routing via Workflow YAML
**As a** platform operator  
**I want** to configure agent routing in the workflow YAML (stages + routing_rules)  
**So that** model selection is version-controlled and project-specific

**Acceptance Criteria:**
- `agent.stages` defines: `coding` → `codex`, `review` → `claude-opus-4.8`, `simple` → `opencode-free`
- `agent.routing_rules` routes by label/state/priority to appropriate agent
- `agent.agents` map defines provider configs with model overrides
- No code changes needed to adjust routing

### US-3: Free Model Cycling with Retry/Backoff
**As a** cost-conscious user  
**I want** OpenCode free tier models to cycle automatically with retry logic  
**So that** simple tasks complete without paid API calls

**Acceptance Criteria:**
- Cycles through OpenCode's built-in free models
- Max 3 retries per model with exponential backoff (1s, 2s, 4s)
- On total exhaustion: marks Linear ticket `Blocked`, writes `.model-exhausted` status file, sends notification (webhook/telegram/email)
- Structured log entry with `model_exhaustion` tag

### US-4: Automatic Review Gate
**As a** quality-focused team  
**I want** Claude Opus 4.8 to automatically review Codex's work  
**So that** code quality is enforced without manual intervention

**Acceptance Criteria:**
- After Codex completes a coding turn → auto-triggers review stage
- Review failure → routes back to Codex with feedback (max `maxReviewAttempts` from stage config)
- Max review attempts exhausted → block ticket + alert
- Review uses existing `scripts/review.sh` infrastructure

## 3. Functional Requirements

### FR-1: Docker Compose Orchestration
- `docker-compose.yml` with services: `koncerto-app`, `koncerto-agent` (base), `postgres` (optional), `redis` (optional)
- `koncerto-app` builds from `koncerto-app/Dockerfile` or uses `./gradlew bootRun` in dev
- `koncerto-agent` image built from `Dockerfile.agent` (multi-stage)
- Shared volumes for workspace, config, logs

### FR-2: Runner Script (`scripts/koncerto-run.sh`)
- Builds JAR: `./gradlew :koncerto-app:bootJar -x test`
- Builds agent image: `docker build -f Dockerfile.agent -t koncerto-agent:latest .`
- Starts docker-compose: `docker-compose up -d`
- Waits for health endpoints (`/actuator/health`)
- Tails logs until Ctrl+C
- Graceful shutdown on SIGTERM

### FR-3: Workflow YAML Configuration
```yaml
agent:
  kind: "opencode"  # default
  stages:
    coding:
      agentKind: "codex"
      model: "codex"
      maxConcurrent: 2
      onCompleteState: "In Review"
    review:
      agentKind: "claude"
      model: "claude-opus-4-8"
      maxConcurrent: 1
      maxReviewAttempts: 3
      onCompleteState: "Done"
      onFailureState: "Blocked"
    simple:
      agentKind: "opencode"
      model: "free"
      maxConcurrent: 3
  routingRules:
    - ifLabelPrefix: "feat/"
      useAgent: "codex"
      priority: 10
    - ifLabel: "review"
      useAgent: "claude"
      priority: 20
    - ifState: "Todo"
      useAgent: "opencode"
      priority: 5
  agents:
    codex:
      kind: "codex"
      command: "codex"
      model: "codex"
    claude:
      kind: "claude"
      command: "claude --model opus-4.8"
      model: "claude-opus-4-8"
    opencode-free:
      kind: "opencode"
      command: "opencode"
      model: "free"
```

### FR-4: FreeModelCycler Component
- Location: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/FreeModelCycler.kt`
- Interface: `suspend fun nextModel(): String` — returns model identifier
- State: tracks current model index, retry counts per model
- On exhaustion: throws `ModelExhaustedException` with details

### FR-5: ModelRetryHandler Component
- Location: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/ModelRetryHandler.kt`
- Wraps agent execution with retry/backoff
- On `ModelExhaustedException`:
  1. Call `LinearClient.updateIssue(issueId, state = "Blocked")`
  2. Write `.model-exhausted` file to workspace with JSON details
  3. Send notification via `CompositeNotifier` (webhook/telegram/email)
  4. Log structured event: `model_exhaustion` with `issueId`, `modelsTried`, `totalRetries`

### FR-6: AutoReviewOrchestrator
- Location: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/AutoReviewOrchestrator.kt`
- Listens for `TurnCompleted` from Codex agent
- Triggers review stage via `AgentRunner` with review prompt
- On review failure: re-queues coding task with feedback
- Tracks attempt count per issue; blocks after `maxReviewAttempts`

## 4. Non-Functional Requirements

| Requirement | Target |
|-------------|--------|
| Startup time (cold) | < 60 seconds |
| Health check interval | 5 seconds |
| Free model backoff | Exponential: 1s, 2s, 4s, max 30s |
| Notification delivery | < 10 seconds |
| Log retention | 7 days (docker logs) |

## 5. Out of Scope

- Kubernetes/Helm deployment
- Multi-tenancy in single docker-compose
- Custom model provider plugins
- UI dashboard for model routing

## 6. Success Metrics

- Developer can run `./scripts/koncerto-run.sh` and have working system in < 60s
- 90%+ of simple tasks complete on free tier without paid API calls
- Zero manual review triggers needed for standard workflow
- Clear alerting when free tier exhausted (ticket blocked + notification)

---