# Architecture: Koncerto Run Configuration with Multi-Model Orchestration

## Decision Log

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Entry Point | docker-compose + shell script | Matches existing DockerRuntime, fully reproducible, zero dependency on host tooling beyond Docker |
| Workflow YAML routing | Stages + routing_rules + agents map | Leverages existing `AgentProjectConfig` parsing, version-controlled, no config schema changes needed |
| Free model cycler | New `FreeModelCycler` in koncerto-agent | Keeps retry logic co-located with agent execution, testable in isolation |
| Alert delivery | Webhook + status file | Webhook for immediate notification, status file for durable traceability |
| Review trigger | Event-driven in orchestartor | Loose coupling: review fires on `TurnCompleted`, not coupled to Codex implementation |

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────┐
│                    User / Developer                      │
│                  runs koncerto-run.sh                    │
└──────────────┬──────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────┐     ┌──────────────────────────┐
│  build.sh                 │     │  docker-compose up       │
│  - gradle bootJar         │────►│  - koncerto-app:8080     │
│  - docker build agent     │     │  - koncerto-agent:stdin  │
└──────────────────────────┘     │  - health checks every 5s│
                                 └──────────┬───────────────┘
                                            │
                                            ▼
┌───────────────────────────────────────────────────────────┐
│                  Koncerto Orchestrator                     │
│  ┌────────────────────────────────────────────────────┐   │
│  │              Task Router (routing_rules)           │   │
│  │  ┌──────────┐  ┌──────────────┐  ┌──────────────┐ │   │
│  │  │f eat/*    │  │label: review │  │state: Todo   │ │   │
│  │  │→ Codex    │  │→ Claude      │  │→ OpenCode    │ │   │
│  │  └──────────┘  └──────────────┘  └──────────────┘ │   │
│  └────────────────────────────────────────────────────┘   │
│                                                           │
│  ┌────────────────────────────────────────────────────┐   │
│  │              AutoReviewOrchestrator                 │   │
│  │   listens TurnCompleted → triggers review stage    │   │
│  │   tracks attempts → blocks at maxReviewAttempts    │   │
│  └────────────────────────────────────────────────────┘   │
│                                                           │
│  ┌────────────────────────────────────────────────────┐   │
│  │              Agent Runner                          │   │
│  │  ┌─────────┐  ┌──────────┐  ┌──────────────────┐ │   │
│  │  │ Codex   │  │ Claude    │  │ OpenCode Free    │ │   │
│  │  │Runtime  │  │ReviewRun  │  │ + FreeModelCycler│ │   │
│  │  └─────────┘  └──────────┘  └──────────────────┘ │   │
│  │                               ┌──────────────────┐ │   │
│  │                               │ ModelRetryHandler│ │   │
│  │                               │ 3 retries/model  │ │   │
│  │                               │ backoff: 1/2/4s  │ │   │
│  └────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────┘
```

## Component Specifications

### C1: scripts/koncerto-run.sh
- **Inputs:** `--dev` (dev mode, no build), `--clean` (rebuild everything)
- **Behavior:**
  1. Parse args
  2. If not `--dev`: `./gradlew :koncerto-app:bootJar -x test` then `docker build -f Dockerfile.agent -t koncerto-agent:latest .`
  3. `docker-compose up -d`  4. Loop until `/actuator/health` returns 200 (max 60s)
  5. `docker-compose logs -f`
  6. On SIGINT/SIGTERM: `docker-compose down`
- **Exit codes:** 0 = healthy, 1 = build fail, 2 = health timeout

### C2: docker-compose.yml
- **Services:**
  - `koncerto-app`: image `koncerto-app:latest` or `build: ./` with Dockerfile, ports `8080:8080`, volumes `./config:/config`, `./workflows:/workflows`
  - `koncerto-agent`: image `koncerto-agent:latest`, `build: {dockerfile: Dockerfile.agent}`, depends_on `koncerto-app`
- **Network:** `koncerto-network` (matches existing `DockerRuntime`)

### C3: FreeModelCycler
- **File:** `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/FreeModelCycler.kt`
- **State:**
  - `models: List<String>` — ordered list of free model IDs
  - `currentIndex: Int` — which model we're on
  - `retryCounts: Map<String, Int>` — retries per model
  - `maxRetriesPerModel: Int = 3`
- **Methods:**
  - `suspend fun nextModel(): Result<String, ModelExhaustedException>`
  - `suspend fun reportFailure(model: String)`
  - `suspend fun reset()`
- **Data class:** `ModelExhaustedException(models: List<String>, totalTries: Int)`

### C4: ModelRetryHandler
- **File:** `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/ModelRetryHandler.kt`
- **Inputs:** `cycler: FreeModelCycler`, `agent: AgentRunner`, `notifier: CompositeNotifier`, `linearClient: LinearClient`
- **Behavior:**
  1. Get next model from cycler
  2. Run agent turn with that model
  3. On failure: report to cycler, exponential backoff (1s, 2s, 4s)
  4. On exhaustion: block ticket → write status file → notify → log
- **Status file format:** JSON with `issueId`, `modelsTried: List<String>`, `totalRetries: Int`, `timestamp`, `reason: String`

### C5: AutoReviewOrchestrator
- **File:** `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/AutoReviewOrchestrator.kt`
- **Depends on:** `AgentRunner`, `RuntimeState`, `StageAgentConfig`
- **Behavior:**
  1. Subscribe to `AgentEvent.TurnCompleted` from Codex agents (identified by logTag)
  2. Look up `stages.review` config from current project
  3. Build review prompt with changed code + original requirements
  4. Dispatch review to Claude via `AgentRunner.dispatch(agentId, prompt)`
  5. Track attempts in `RuntimeState` (new key: `attempts` per issue)
  6. On review fail: check `attempts < maxReviewAttempts` → re-route to Codex with feedback
  7. On max attempts: call `blockIssue()` → status file → notify

### C6: Workflow YAML Config Changes
- **File:** Workflow markdown frontmatter
- **New fields used (all already parsed):**
  - `agent.stages` — stage definitions for coding/review/simple
  - `agent.routing_rules` — label/state-based routing
  - `agent.agents` — named agent provider configs
- **Sample workflow file:** `workflows/koncerto-multi-model.md`

## Data Flow

### Happy Path (Coding → Review → Done)
```
1. Task arrives with label "feat/add-search"
2. routing_rules: feat/* → useAgent: "codex"
3. CodexRuntime codes the feature
4. TurnCompleted fires
5. AutoReviewOrchestrator triggers review stage
6. ClaudeReviewRuntime runs review
7. Review passes → ticket → "Done"
```

### Unhappy Path (Model Exhaustion)
```
1. Simple task routed to opencode-free
2. FreeModelCycler.nextModel() → model-1
3. Model fails → retry × 3 → model-2 → retry × 3 → all exhausted
4. ModelExhaustedException thrown
5. ModelRetryHandler catches:
   a. linearClient.updateIssue(id, "Blocked")
   b. Write .model-exhausted to workspace
   c. compositeNotifier.send(...)
   d. logger.warn("model_exhaustion", ...)
```

## Testing Strategy

| Component | Test Type | Key Tests |
|-----------|-----------|-----------|
| FreeModelCycler | Unit | Cycle through 3 models, retry tracking, exhaustion, reset |
| ModelRetryHandler | Unit | Successful retry, exhaustion flow, status file write |
| AutoReviewOrchestrator | Unit | Handles TurnCompleted, triggers review, manages attempts |
| koncerto-run.sh | Manual | Run with `--dev`, `--clean`, health check timeout |
| docker-compose.yml | Manual | `docker-compose up`, verify app health endpoint |
| Workflow YAML | Integration | Parsing multi-model config, routing rules match |

## Security & Error Handling

- No secrets in docker-compose; use `$VARIABLE` references
- `ModelExhaustedException` includes full context for debugging
- Health check failure → explicit exit code + error message
- All file writes use atomic write pattern (temp file + rename)
- Notification delivery failure does not block ticket blocking
