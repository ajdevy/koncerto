# Product Requirements Document: Koncerto

**Version:** 2.0  
**Date:** 2026-06-25  
**PM:** Sarah the PM  
**Status:** Updated (v2.0: demo recording, auto-deploy, auto-review, metrics, notifications, admin API)  

---

## 1. Executive Summary

Koncerto is a Kotlin/Spring Boot orchestration service that automates software development workflows by connecting project trackers (Linear) with AI coding agents (Codex, opencode). It automatically discovers issues, dispatches them to isolated workspaces, manages agent lifecycles, and provides real-time visibility through a dashboard.

## 2. Problem Statement

Software teams using Linear for issue tracking and AI agents for code generation lack a unified orchestration layer that:
- Automatically picks up issues when they enter active states
- Manages concurrent agent execution with isolation
- Handles failures with retry logic
- Provides visibility into agent activity
- Enforces workflow conventions via configurable templates

## 3. Target Users

### 3.1 Engineering Lead
- **Role:** Manages development team and workflows
- **Needs:** Automate routine tasks, monitor agent activity, ensure quality
- **Pain Points:** Manual issue assignment, no visibility into agent work, inconsistent workflows

### 3.2 Developer
- **Role:** Writes code and implements features
- **Needs:** Focus on complex tasks, reduce context switching
- **Pain Points:** Repetitive bug fixes, manual setup, unclear requirements

### 3.3 DevOps Engineer
- **Role:** Manages infrastructure and deployment
- **Needs:** Reliable automation, observability, minimal maintenance
- **Pain Points:** Unreliable automation, poor logging, difficult debugging

## 4. Goals & Success Metrics

| Goal | Metric | Target | Measurement |
|------|--------|--------|-------------|
| Reduce manual handoff | Time from issue to PR | < 15 minutes | Linear API timestamps |
| Reliable execution | Completion rate | > 90% | Completed / Total |
| Agent isolation | Cross-workspace interference | 0% | Zero incidents |
| Observability | Mean time to detect failure | < 2 minutes | Dashboard + alerts |
| Configurability | Time to add new workflow | < 30 minutes | User testing |

## 5. Functional Requirements

### 5.1 Issue Polling & Discovery

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-01 | Poll Linear API at configurable intervals | P0 | Polls every N ms, configurable |
| FR-02 | Filter issues by active states | P0 | Only processes issues in active states |
| FR-03 | Respect priority ordering | P0 | Higher priority issues dispatch first |
| FR-04 | Support label-based filtering | P1 | Can require specific labels |
| FR-05 | Skip blocked issues | P1 | Issues blocked by unfinished deps are skipped |

### 5.2 Workspace Management

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-06 | Create isolated workspace per issue | P0 | Each issue gets own directory |
| FR-07 | Execute shell hooks | P0 | after_create, before_run, after_run, before_remove |
| FR-08 | Clean up workspaces | P0 | Remove directory on completion |
| FR-09 | Configurable workspace root | P0 | Root directory is configurable |

### 5.3 Agent Execution

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-10 | Spawn Codex/opencode subprocess via JSON-RPC | P0 | Process starts and communicates via JSON-RPC |
| FR-11 | Render prompt templates with Liquid | P0 | {{ issue.identifier }} placeholders work |
| FR-12 | Stream agent events | P0 | Emit AgentEvent sealed class variants |
| FR-13 | Enforce max turns per issue | P0 | Stop after max turns reached |
| FR-14 | Enforce concurrency limits | P0 | Respect max concurrent agents |
| FR-15 | Handle stall/timeout | P0 | Detect and kill unresponsive agents |

### 5.4 Lifecycle Management

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-16 | Detect terminal states | P0 | Reclaim resources when issue completes |
| FR-17 | Retry with exponential backoff | P0 | 10s * 2^(attempt-1), capped at max |
| FR-18 | Track token usage | P1 | Count input/output tokens per attempt |
| FR-19 | Per-state concurrency limits | P1 | Different limits for different states |

### 5.5 Dependency & Blocking

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-23 | Build dependency graph from blockers | P1 | Graph built from candidate issues' blockedBy |
| FR-24 | Compute dispatch frontier | P1 | Frontier contains only unblocked issues |
| FR-25 | Respect external blockers | P1 | Blockers absent from candidates treated as resolved |
| FR-26 | Configure routing rules for agent selection | P1 | Rules match by label/state/priority |
| FR-27 | Create follow-up issues on completion | P2 | Issue created via Linear API with rendered template |

### 5.6 Dashboard & API

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-28 | JSON API for state | P1 | GET /api/v1/state returns snapshot |
| FR-29 | HTML dashboard | P2 | Live-refreshing table at / |
| FR-30 | Manual refresh | P1 | POST /api/v1/refresh triggers poll |
| FR-31 | Query issue status | P1 | GET /api/v1/{identifier} returns details |

### 5.7 Demo Recording

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-32 | Record web app demos via Playwright | P1 | Launches Chromium, navigates to URL, captures 120s video |
| FR-33 | Upload recordings to R2/S3-compatible storage | P1 | SigV4-authenticated PUT with presigned URL support |
| FR-34 | Support multiple recorder backends | P2 | Playwright (web), Asciinema (terminal), ADB (Android) |
| FR-35 | Convert raw capture to compressed video via ffmpeg | P1 | H.264/VP9 codec, configurable FPS and resolution |

### 5.8 Auto-Review Pipeline

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-36 | Run Claude Code review on completed PRs | P1 | Reads diff, runs claude --print with review prompt |
| FR-37 | Post review results as PR comment | P1 | Formats output as GH comment with verdict header |
| FR-38 | Support review pass/fail gating | P1 | ❌ FAIL blocks state transition, ✅ PASS proceeds |
| FR-39 | Strip preamble and config noise from review output | P1 | Filters stderr config errors and conversational preamble |

### 5.9 Target Project Auto-Deploy

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-40 | Auto-detect Docker config in target project | P1 | Detects docker-compose.yml or Dockerfile |
| FR-41 | Detect project framework for Dockerfile generation | P1 | Spring Boot, Node.js, Python, Go |
| FR-42 | Build and run target project in Docker | P1 | Multi-stage build, port allocation, health check |
| FR-43 | Clean up containers and images after demo | P1 | Force-remove containers, delete image, compose down |

### 5.10 Notifications

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-44 | Webhook notifications on issue completion | P2 | POST JSON payload to configured URL |
| FR-45 | Telegram notifications | P2 | Send formatted message to configured chat |
| FR-46 | Email notifications via SMTP | P2 | Send plain-text email on configured events |
| FR-47 | Composite notifier fans out to all channels | P2 | Single Notifier interface dispatches to all configured channels |

### 5.11 Metrics & Monitoring

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-48 | SQLite-backed metrics store | P1 | Local file-based storage for issue and system metrics |
| FR-49 | Prometheus endpoint at /actuator/prometheus | P1 | Micrometer-registered metrics exposed for scraping |
| FR-50 | JVM, HTTP, and custom business metrics | P2 | Memory, threads, GC, dispatch count, retry count |

### 5.12 Admin API

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-51 | Admin endpoints at /api/v1/admin/ with API key auth | P1 | X-Admin-Key header required, returns 401 on mismatch |
| FR-52 | Config read/write via API | P2 | GET/PUT /api/v1/config for live configuration management |
| FR-53 | Dependency graph API | P2 | GET /api/v1/dependencies returns nodes/edges as JSON |

## 6. Non-Functional Requirements

| ID | Requirement | Target | Measurement |
|----|-------------|--------|-------------|
| NFR-01 | Startup time | < 5 seconds | Cold start measurement |
| NFR-02 | Memory footprint | < 512 MB | RSS monitoring |
| NFR-03 | Concurrent workspaces | 10+ | Load testing |
| NFR-04 | Test coverage | > 80% (current: 67%) | JaCoCo reports |
| NFR-05 | License | MIT | License file |
| NFR-06 | Demo recording duration | < 180 seconds | Playwright + ffmpeg + R2 upload |
| NFR-07 | Docker image size | < 500 MB | Multi-stage build |

## 7. Epics & Stories

### Epic 1: Core Foundation (8 points)

#### Story 1.1: Result Wrapper
- **As a** developer
- **I want** a Result wrapper
- **So that** errors are explicitly handled
- **Acceptance Criteria:**
  - Result<T, E> supports map, onSuccess, onFailure, getOrNull
  - Unit tests cover all methods

#### Story 1.2: Issue Model
- **As a** developer
- **I want** an Issue model
- **So that** tracker data is strongly typed
- **Acceptance Criteria:**
  - Issue data class with all Linear fields
  - normalizedState property
  - Unit tests

#### Story 1.3: WorkflowDefinition
- **As a** developer
- **I want** to parse workflow definitions
- **So that** config is separated from content
- **Acceptance Criteria:**
  - Parse YAML front matter from markdown
  - Validate required fields
  - Unit tests

#### Story 1.4: ServiceConfig
- **As a** developer
- **I want** a ServiceConfig
- **So that** runtime settings are centralized
- **Acceptance Criteria:**
  - Config with env resolution, path expansion
  - Validation rules
  - Unit tests

### Epic 2: Structured Logging (5 points)

#### Story 2.1: StructuredLogger
- **As a** developer
- **I want** a StructuredLogger
- **So that** logs are machine-parseable
- **Acceptance Criteria:**
  - Key-value pair logging
  - Support info, warn, failure levels
  - Unit tests

#### Story 2.2: Log Sinks
- **As an** operator
- **I want** multiple log destinations
- **So that** logs go where needed
- **Acceptance Criteria:**
  - StderrSink writes to stderr
  - FileSink writes to files
  - CompositeSink fans out
  - Unit tests

### Epic 3: Workflow Engine (8 points)

#### Story 3.1: YAML Front Matter Parser
- **As a** developer
- **I want** to parse YAML front matter
- **So that** config is separated from content
- **Acceptance Criteria:**
  - Parse --- delimited YAML
  - Extract config and content
  - Unit tests

#### Story 3.2: Liquid Template Rendering
- **As a** developer
- **I want** Liquid template rendering
- **So that** prompts include issue data
- **Acceptance Criteria:**
  - {{ issue.identifier }} placeholders work
  - Unknown vars are handled gracefully
  - Unit tests

#### Story 3.3: WorkflowCache
- **As a** developer
- **I want** a WorkflowCache
- **So that** templates are loaded once
- **Acceptance Criteria:**
  - Cache current workflow
  - Support reload
  - Unit tests

### Epic 4: Workspace Management (8 points)

#### Story 4.1: Workspace Creation
- **As a** developer
- **I want** workspace creation
- **So that** agents have isolated dirs
- **Acceptance Criteria:**
  - Create directory under root
  - Name by issue identifier
  - Unit tests

#### Story 4.2: Shell Hooks
- **As an** operator
- **I want** shell hooks
- **So that** setup/teardown runs automatically
- **Acceptance Criteria:**
  - Execute after_create, before_run, etc.
  - Configurable timeout
  - Unit tests

#### Story 4.3: Workspace Removal
- **As a** developer
- **I want** workspace removal
- **So that** disk space is reclaimed
- **Acceptance Criteria:**
  - Remove directory and contents
  - Unit tests

### Epic 5: Linear Integration (13 points)

#### Story 5.1: Fetch Candidate Issues
- **As a** developer
- **I want** to fetch candidate issues
- **So that** work can be dispatched
- **Acceptance Criteria:**
  - Query by project slug and active states
  - Handle pagination
  - Unit tests

#### Story 5.2: Fetch Issue States
- **As a** developer
- **I want** to fetch issue states
- **So that** reconciliation works
- **Acceptance Criteria:**
  - Query states by issue IDs
  - Unit tests

#### Story 5.3: Issue Mapping
- **As a** developer
- **I want** issue mapping
- **So that** Linear responses become Issue objects
- **Acceptance Criteria:**
  - Map GraphQL response to Issue
  - Handle blockers, labels
  - Unit tests

### Epic 6: Agent Runtime (25 points)

#### Story 6.1: JSON-RPC Framing
- **As a** developer
- **I want** JSON-RPC framing
- **So that** messages are parsed correctly
- **Acceptance Criteria:**
  - Parse Content-Length framed messages
  - Unit tests

#### Story 6.2: Event Streaming
- **As a** developer
- **I want** event streaming
- **So that** agent activity is visible
- **Acceptance Criteria:**
  - Emit AgentEvent sealed class variants
  - Unit tests

#### Story 6.3: Agent Abstraction Layer
- **As a** developer
- **I want** an agent abstraction layer
- **So that** multiple agent runtimes can be supported
- **Acceptance Criteria:**
  - AgentRuntime interface with spawn, send, stop methods
  - Factory for creating runtime instances
  - Unit tests

#### Story 6.4: Codex Runtime Implementation
- **As a** developer
- **I want** Codex runtime implementation
- **So that** Codex agents can be spawned
- **Acceptance Criteria:**
  - Spawn codex app-server
  - Manage stdin/stdout pipes
  - Handle Codex-specific JSON-RPC protocol
  - Unit tests

#### Story 6.5: opencode Runtime Implementation
- **As a** developer
- **I want** opencode runtime implementation
- **So that** opencode agents can be spawned
- **Acceptance Criteria:**
  - Spawn opencode subprocess
  - Manage stdin/stdout pipes
  - Handle opencode-specific JSON-RPC protocol
  - Unit tests

#### Story 6.6: Turn Timeout
- **As a** developer
- **I want** turn timeout
- **So that** hangs don't block forever
- **Acceptance Criteria:**
  - Configurable timeout per turn
  - Kill process on timeout
  - Unit tests

#### Story 6.7: Stall Detection
- **As a** developer
- **I want** stall detection
- **So that** unresponsive agents are killed
- **Acceptance Criteria:**
  - Detect no-output conditions
  - Terminate after timeout
  - Unit tests

### Epic 7: Orchestration (21 points)

#### Story 7.1: Polling
- **As an** operator
- **I want** polling
- **So that** new issues are discovered
- **Acceptance Criteria:**
  - Poll at configurable interval
  - Unit tests

#### Story 7.2: Dispatch
- **As a** developer
- **I want** dispatch
- **So that** issues are assigned to agents
- **Acceptance Criteria:**
  - Create workspace
  - Render prompt
  - Start agent
  - Unit tests

#### Story 7.3: Retry
- **As a** developer
- **I want** retry
- **So that** transient failures recover
- **Acceptance Criteria:**
  - Exponential backoff
  - Configurable max
  - Unit tests

#### Story 7.4: Reconciliation
- **As a** developer
- **I want** reconciliation
- **So that** completed issues are detected
- **Acceptance Criteria:**
  - Check against terminal states
  - Clean up resources
  - Unit tests

#### Story 7.5: Concurrency Limits
- **As an** operator
- **I want** concurrency limits
- **So that** resources are bounded
- **Acceptance Criteria:**
  - Max concurrent agents
  - Per-state limits
  - Unit tests

#### Story 7.6: Priority Sorting
- **As a** developer
- **I want** priority sorting
- **So that** important issues run first
- **Acceptance Criteria:**
  - Sort by priority, then creation date
  - Unit tests

### Epic 8: Dashboard & API (8 points)

#### Story 8.1: JSON API
- **As an** operator
- **I want** a JSON API
- **So that** state can be queried programmatically
- **Acceptance Criteria:**
  - GET /api/v1/state returns snapshot
  - Unit tests

#### Story 8.2: HTML Dashboard
- **As an** operator
- **I want** an HTML dashboard
- **So that** state is visible at a glance
- **Acceptance Criteria:**
  - Live-refreshing table
  - Shows running/retrying issues
  - Unit tests

#### Story 8.3: Issue Lookup
- **As an** operator
- **I want** issue lookup
- **So that** specific issues can be inspected
- **Acceptance Criteria:**
  - GET /api/v1/{identifier}
  - Unit tests

#### Story 8.4: Manual Refresh
- **As an** operator
- **I want** manual refresh
- **So that** state can be forced
- **Acceptance Criteria:**
  - POST /api/v1/refresh
  - Unit tests

### Epic 9: Application Assembly (5 points)

#### Story 9.1: CLI Runner
- **As an** operator
- **I want** a CLI runner
- **So that** the workflow file is specified
- **Acceptance Criteria:**
  - Accept workflow path as argument
  - Unit tests

#### Story 9.2: Bean Wiring
- **As an** operator
- **I want** bean wiring
- **So that** dependencies are injected
- **Acceptance Criteria:**
  - @Configuration class
  - All beans defined
  - Unit tests

#### Story 9.3: Bootable JAR
- **As an** operator
- **I want** a bootable JAR
- **So that** deployment is simple
- **Acceptance Criteria:**
  - bootJar task works
  - Executable JAR produced

### Epic 10: Blocker State Tracking & Parallel Execution Groups (13 points)

#### Story 10.1: Blocker State Tracking
- **As a** developer
- **I want** the orchestrator to track which issues are blocked and auto-unblock when blockers resolve
- **So that** the dispatch system always has an accurate picture of available work
- **Acceptance Criteria:**
  - reconcile() checks blocker states after state cleanup
  - Issues whose blockers all reached terminal states are marked unblocked
  - RuntimeState.blocked set is correctly maintained
  - Unit tests cover all scenarios

#### Story 10.2: Dependency Graph & Frontier
- **As a** developer
- **I want** a dependency graph with frontier computation
- **So that** only unblocked issues are dispatched
- **Acceptance Criteria:**
  - DependencyGraph data class with nodes, edges, frontier
  - Frontier contains issues with no unresolved blockers
  - Blocker absent from candidates → treated as resolved
  - Unlinked blocker → treated as resolved
  - Unit tests for chain, diamond, all-blocked graphs

#### Story 10.3: Block-Aware Dispatch
- **As a** developer
- **I want** fetchAndDispatch() to use the dependency frontier
- **So that** the system dispatches all available unblocked issues
- **Acceptance Criteria:**
  - fetchAndDispatch() builds DependencyGraph from candidates
  - Dispatches from frontier instead of filtered list
  - Respects concurrency limits
  - Blocked issues tracked in state.blocked for dashboard

#### Story 10.4: Dashboard Blocker Visibility
- **As an** operator
- **I want** to see blocked issues and their blockers in the dashboard
- **So that** I can understand why work is not progressing
- **Acceptance Criteria:**
  - API exposes blocked state and blocker identifiers
  - Dashboard shows blocked status per issue

### Epic 11: Agent Specialization Routing (8 points)

#### Story 11.1: Routing Rule Config
- **As an** operator
- **I want** to configure routing rules mapping issues to agents
- **So that** different issue types use appropriate agent configs
- **Acceptance Criteria:**
  - RoutingRule data class with label, state, priority conditions
  - routingRules field in AgentProjectConfig
  - YAML parsing with priority sort
  - Unit tests

#### Story 11.2: Routing Rule Evaluation
- **As a** developer
- **I want** resolveAgent() to evaluate routing rules
- **So that** rules take effect before default resolution
- **Acceptance Criteria:**
  - evaluateRoutingRules() method in DispatchService
  - First matching rule wins
  - Fall through to existing logic if no match
  - Unit tests for all conditions

#### Story 11.3: Routing Integration
- **As a** developer
- **I want** routing rules integrated with stage configs and label overrides
- **So that** the resolution chain is predictable
- **Acceptance Criteria:**
  - Resolution order: stage > label > routing > default
  - Missing useAgent key → warn log, fallback
  - Unit tests for priority chain

### Epic 12: Workflow Chaining (10 points)

#### Story 12.1: FollowUp Config
- **As an** operator
- **I want** to configure follow-up issue creation when issues complete
- **So that** downstream work is created automatically
- **Acceptance Criteria:**
  - FollowUpConfig data class with titleTemplate, state, labels, linkType
  - followUp field on StageAgentConfig
  - YAML parsing with validation
  - Unit tests

#### Story 12.2: Create Issue & Link API
- **As a** developer
- **I want** LinearClient to support creating issues and linking them
- **So that** workflow chaining can create follow-up issues
- **Acceptance Criteria:**
  - linear.createIssue() returns Issue?
  - linear.createLink() returns Boolean
  - GraphQL mutations for issueCreate and issueRelationCreate
  - Handle API errors gracefully

#### Story 12.3: Template Rendering
- **As a** developer
- **I want** follow-up templates rendered with issue data
- **So that** follow-ups are contextual
- **Acceptance Criteria:**
  - FollowUpRenderer supports {{ issue.title }}, {{ issue.identifier }}, {{ now }}, etc.
  - Unknown variables left as-is
-   Unit tests for all variables

#### Story 12.4: Chain Execution
- **As a** developer
- **I want** transitionOnComplete() to create follow-ups when configured
- **So that** the chain is automated end-to-end
- **Acceptance Criteria:**
  - Creates follow-up issue on onCompleteState transition
  - Renders templates with issue data
  - Creates link between source and follow-up
  - Handles API failures gracefully
  - Logs chain creation event

### Epic 13: Beyond-Scope Features (55 points)

*Status: Most sub-epics complete (13.01-13.11 planned, 13.01-13.10 implemented)*

#### Story 13.1: Docker Deployment
- **As an** operator
- **I want** Docker deployment
- **So that** the service is containerized
- **Acceptance Criteria:**
  - [x] Multi-stage Dockerfile with Gradle build stage
  - [x] docker-compose.yml with app + ngrok services
  - [x] HEALTHCHECK on dashboard port
  - [x] Non-root user in runtime stage

#### Story 13.2: Prometheus Monitoring
- **As a** DevOps engineer
- **I want** Prometheus metrics
- **So that** I can monitor the service
- **Acceptance Criteria:**
  - [x] /actuator/prometheus endpoint exposed
  - [x] SQLite-backed metrics repository
  - [x] JVM, HTTP, and custom business metrics

#### Story 13.3: Conventional Commits
- **As a** developer
- **I want** conventional commit prefixes
- **So that** commit messages follow convention
- **Acceptance Criteria:**
  - [x] commitPrefix() maps branch names to commit types
  - [x] Automated commit prefix in workflows

#### Story 13.4: Dashboard Authentication
- **As an** operator
- **I want** admin API key protection
- **So that** dashboard endpoints are secured
- **Acceptance Criteria:**
  - [x] X-Admin-Key header required on /api/v1/admin/**
  - [x] Configurable via admin.apiKey in workflow config

#### Story 13.5: Config Editor UI
- **As an** operator
- **I want** to read/write config via API
- **So that** live configuration is manageable
- **Acceptance Criteria:**
  - [x] GET/PUT /api/v1/config endpoints
  - [x] Config schema endpoint

#### Story 13.6: Visual Dependency Graph API
- **As an** operator
- **I want** to query the dependency graph
- **So that** I can visualize issue relationships
- **Acceptance Criteria:**
  - [x] GET /api/v1/dependencies returns nodes/edges
  - [x] Built from orchestrator RuntimeState

#### Story 13.7: Agent Messaging
- **As a** developer
- **I want** inter-agent message passing
- **So that** agents can coordinate
- **Acceptance Criteria:**
  - [x] AgentMessageStore with send/poll/ack/list
  - [x] In-memory + SQLite implementations

#### Story 13.8: Automated PR Creation
- **As a** developer
- **I want** automated PR creation
- **So that** PRs are created without manual intervention
- **Acceptance Criteria:**
  - [x] gh pr create integration in GitWorkflow
  - [x] Configurable base branch, title, body

#### Story 13.9: GitHub Issues Bridge
- **As a** developer
- **I want** a generic TrackerClient interface
- **So that** multiple issue trackers are supported
- **Acceptance Criteria:**
  - [x] TrackerClient interface with CRUD operations
  - [x] Provider-agnostic pagination

#### Story 13.10: E2E CI Pipeline
- **As a** DevOps engineer
- **I want** CI/CD via GitHub Actions
- **So that** tests run on every PR
- **Acceptance Criteria:**
  - [x] .github/workflows/ci.yml for build + test
  - [x] .github/workflows/e2e.yml for end-to-end
  - [x] .github/workflows/docker-publish.yml for images

#### Story 13.11: Test Coverage Expansion
- **As a** developer
- **I want** higher test coverage
- **So that** code quality is maintained
- **Acceptance Criteria:**
  - [ ] Coverage threshold > 80% (current: 67%)
  - [ ] Testcontainers-based integration tests

### Epic 14: Scalable Multi-Project (8 points)

*Status: Partially implemented*

#### Story 14.1: Tenant Resolution
- **As an** operator
- **I want** multi-tenant support
- **So that** a single instance serves multiple projects
- **Acceptance Criteria:**
  - [x] TenantId, TenantContext, TenantResolver interfaces
  - [x] ConfigTenantResolver reads from project config
  - [ ] Tenant-scoped rate limits and quotas
  - [ ] Tenant-aware dashboard filtering

### Epic 15: Cloud-Native Deploy (8 points)

*Status: Not implemented*

#### Story 15.1: Helm Chart
- **As a** DevOps engineer
- **I want** Kubernetes deployment
- **So that** the service runs on K8s
- **Acceptance Criteria:**
  - [ ] Helm chart with Deployment, Service, ConfigMap
  - [ ] Multi-environment values files

### Epic 16: Claude Code Post-Story Code Reviews (34 points)

#### Story 16.1: Review Prompt Template
- **As a** developer
- **I want** a Claude Code review prompt
- **So that** code reviews are automated
- **Acceptance Criteria:**
  - [x] prompt-template.md with severity categories
  - [x] Koncerto-specific review standards

#### Story 16.2: Review Runtime (ClaudeReviewRuntime)
- **As a** developer
- **I want** a Claude CLI runtime
- **So that** reviews run in the agent pipeline
- **Acceptance Criteria:**
  - [x] Spawns claude --print subprocess
  - [x] Filters config errors and conversational preamble
  - [x] Writes structured output to .review-output

#### Story 16.3: Auto-Review Orchestrator
- **As a** developer
- **I want** automated review orchestration
- **So that** reviews happen after implementation
- **Acceptance Criteria:**
  - [x] AutoReviewOrchestrator runs review after coding completes
  - [x] Verdict parsing: explicit ❌ FAIL → review failed
  - [x] postDetailedReviewAsPrComment() posts formatted comment
  - [x] Review history tracked with sequence counter

#### Story 16.4: Fix Loop Integration
- **As a** developer
- **I want** review findings to trigger fixes
- **So that** critical issues are resolved automatically
- **Acceptance Criteria:**
  - [x] prompts/fix-review.md for fix instructions
  - [x] Re-dispatch on review failure
  - [x] Max review attempts before blocking

### Epic 17: ngrok Tunnel (5 points)

#### Story 17.1: Custom Dashboard Port
- **As an** operator
- **I want** a configurable dashboard port
- **So that** the dashboard doesn't conflict with other services
- **Acceptance Criteria:**
  - [x] Dashboard on port 17348 (configurable via KONCERTO_DASHBOARD_PORT)
  - [x] ngrok tunnel with OAuth auto-start

#### Story 17.2: Tunnel Lifecycle
- **As an** operator
- **I want** ngrok tunnel management
- **So that** the dashboard is accessible externally
- **Acceptance Criteria:**
  - [x] Tunnel auto-starts with the application
  - [x] TunnelController exposes tunnel status in dashboard

## 8. Out of Scope

- Full visual dependency graph in dashboard (API exists, no JS graph renderer)
- Event-driven blocker tracking (poll-time only)
- Cross-project dependency resolution
- Kubernetes/Helm deployment (Epic 15 - not yet implemented)
- Multi-region / high-availability deployment
- Native mobile app for monitoring

## 9. Open Questions

| Question | Answer | Date |
|----------|--------|------|
| Should we support GitHub Issues? | Post-v1 | TBD |
| Should we support custom agent commands? | Yes, via codex.command / opencode.command | 2026-06-08 |
| What are the differences in JSON-RPC protocol between Codex and opencode? | Research needed | TBD |
| How should users configure which agent to use per workflow? | agent.kind field in workflow | TBD |
| Should we support explicit batch labels? | Post-v1 (auto-frontier covers this) | 2026-06-09 |
| Should workflow chaining support sub-task templates? | Post-v1 (simple follow-up first) | 2026-06-09 |

## 10. Appendix

### 10.1 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| LINEAR_API_KEY | Linear API key | — |
| KONCERTO_WORKFLOW_PATH | Path to workflow file | ./WORKFLOW.md |
| KONCERTO_LOGS_ROOT | Log directory | (stderr only) |
| KONCERTO_WORKSPACE_ROOT | Workspace root | /tmp/koncerto_workspaces |
| KONCERTO_WEB_TYPE | Web mode | none |
| KONCERTO_CODEX_COMMAND | Codex command | codex app-server |
| KONCERTO_OPENCODE_COMMAND | opencode command | opencode |
| KONCERTO_DASHBOARD_PORT | Dashboard port | 17348 |
| GIT_REMOTE_URL | Default git remote URL | — |
| GITHUB_TOKEN | GitHub CLI auth token | — |
| DEMO_TARGET_URL | URL for Playwright demo recording | — |
| R2_ENDPOINT | R2/S3 endpoint for demo uploads | — |
| R2_ACCESS_KEY | R2 access key | — |
| R2_SECRET_KEY | R2 secret key | — |
| R2_BUCKET | R2 bucket name | — |
| R2_PUBLIC_URL_BASE | R2 public URL base for presigned URLs | — |

### 10.2 Glossary

| Term | Definition |
|------|------------|
| Workspace | Isolated directory for an agent working on a specific issue |
| Turn | Single agent execution cycle (prompt → response) |
| Attempt | Complete execution of an issue (may include multiple turns) |
| Reconciliation | Process of checking running issues against tracker state |
| Backoff | Increasing delay between retry attempts |
| Demo Recording | Automated Playwright capture of deployed app for PR comments |
| Auto-Review | Claude Code review run against completed PR before state transition |
| Target Project | The external project whose Linear issues Koncerto dispatches |
