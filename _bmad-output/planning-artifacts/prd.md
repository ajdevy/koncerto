# Product Requirements Document: Koncerto

**Version:** 1.0  
**Date:** 2026-06-08  
**PM:** Sarah the PM  
**Status:** Approved  

---

## 1. Executive Summary

Koncerto is a Kotlin/Spring Boot orchestration service that automates software development workflows by connecting project trackers (Linear) with AI coding agents (Codex). It automatically discovers issues, dispatches them to isolated workspaces, manages agent lifecycles, and provides real-time visibility through a dashboard.

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
| FR-10 | Spawn Codex subprocess via JSON-RPC | P0 | Process starts and communicates via JSON-RPC |
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

### 5.5 Dashboard & API

| ID | Requirement | Priority | Acceptance Criteria |
|----|-------------|----------|---------------------|
| FR-20 | JSON API for state | P1 | GET /api/v1/state returns snapshot |
| FR-21 | HTML dashboard | P2 | Live-refreshing table at / |
| FR-22 | Manual refresh | P1 | POST /api/v1/refresh triggers poll |
| FR-23 | Query issue status | P1 | GET /api/v1/{identifier} returns details |

## 6. Non-Functional Requirements

| ID | Requirement | Target | Measurement |
|----|-------------|--------|-------------|
| NFR-01 | Startup time | < 5 seconds | Cold start measurement |
| NFR-02 | Memory footprint | < 512 MB | RSS monitoring |
| NFR-03 | Concurrent workspaces | 10+ | Load testing |
| NFR-04 | Test coverage | > 80% | JaCoCo reports |
| NFR-05 | License | MIT | License file |

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

### Epic 6: Agent Runtime (21 points)

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

#### Story 6.3: Subprocess Management
- **As a** developer
- **I want** subprocess management
- **So that** agents are isolated
- **Acceptance Criteria:**
  - Spawn codex app-server
  - Manage stdin/stdout pipes
  - Unit tests

#### Story 6.4: Turn Timeout
- **As a** developer
- **I want** turn timeout
- **So that** hangs don't block forever
- **Acceptance Criteria:**
  - Configurable timeout per turn
  - Kill process on timeout
  - Unit tests

#### Story 6.5: Stall Detection
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

## 8. Out of Scope

- Web UI for configuration editing
- Multi-project support in single instance
- Agent-to-agent communication
- Custom agent runtime (only Codex)
- Persistent audit logging

## 9. Open Questions

| Question | Answer | Date |
|----------|--------|------|
| Should we support GitHub Issues? | Post-v1 | TBD |
| Should we support custom agent commands? | Yes, via codex.command | 2026-06-08 |

## 10. Appendix

### 10.1 Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| LINEAR_API_KEY | Linear API key | — |
| KONCERTO_WORKFLOW_PATH | Path to workflow file | ./WORKFLOW.md |
| KONCERTO_LOGS_ROOT | Log directory | (stderr only) |
| KONCERTO_WORKSPACE_ROOT | Workspace root | /tmp/symphony_workspaces |
| KONCERTO_WEB_TYPE | Web mode | none |

### 10.2 Glossary

| Term | Definition |
|------|------------|
| Workspace | Isolated directory for an agent working on a specific issue |
| Turn | Single agent execution cycle (prompt → response) |
| Attempt | Complete execution of an issue (may include multiple turns) |
| Reconciliation | Process of checking running issues against tracker state |
| Backoff | Increasing delay between retry attempts
