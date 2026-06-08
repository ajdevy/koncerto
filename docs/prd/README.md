# Koncerto — Product Requirements Document

**Version:** 1.0  
**Date:** 2026-06-08  
**Status:** Approved  

---

## 1. Executive Summary

Koncerto is a Kotlin/Spring Boot orchestration service that automates software development workflows by connecting project trackers (Linear) with AI coding agents (Codex). It polls for issues, dispatches them to isolated workspaces, manages agent lifecycles, and provides real-time visibility through a dashboard.

## 2. Problem Statement

Software teams using Linear for issue tracking and AI agents for code generation lack a unified orchestration layer that:

- Automatically picks up issues when they enter active states
- Manages concurrent agent execution with isolation
- Handles failures with retry logic
- Provides visibility into agent activity
- Enforces workflow conventions via configurable templates

## 3. Target Users

| Persona | Description |
|---------|-------------|
| **Engineering Lead** | Wants to automate issue-to-code workflows, monitor agent activity |
| **Developer** | Wants agents to handle routine tasks (bug fixes, refactoring) |
| **DevOps Engineer** | Wants reliable, observable automation infrastructure |

## 4. Goals & Success Metrics

| Goal | Metric | Target |
|------|--------|--------|
| Reduce manual issue-to-code handoff | Time from issue creation to PR | < 15 minutes |
| Reliable execution | Successful completion rate | > 90% |
| Agent isolation | Zero cross-workspace interference | 100% |
| Observability | Mean time to detect failure | < 2 minutes |
| Configurability | Time to add new workflow | < 30 minutes |

## 5. Functional Requirements

### 5.1 Issue Polling & Discovery

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-01 | Poll Linear API at configurable intervals | P0 |
| FR-02 | Filter issues by active states | P0 |
| FR-03 | Respect priority ordering (higher priority first) | P0 |
| FR-04 | Support label-based filtering | P1 |
| FR-05 | Skip issues blocked by unfinished dependencies | P1 |

### 5.2 Workspace Management

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-06 | Create isolated workspace per issue | P0 |
| FR-07 | Execute shell hooks (after_create, before_run, after_run) | P0 |
| FR-08 | Clean up workspaces on completion | P0 |
| FR-09 | Configurable workspace root directory | P0 |

### 5.3 Agent Execution

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-10 | Spawn Codex subprocess via JSON-RPC | P0 |
| FR-11 | Render prompt templates with issue context (Liquid) | P0 |
| FR-12 | Stream agent events (message, turn_completed, error) | P0 |
| FR-13 | Enforce max turns per issue | P0 |
| FR-14 | Enforce concurrency limits | P0 |
| FR-15 | Handle stall/timeout conditions | P0 |

### 5.4 Lifecycle Management

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-16 | Detect terminal states and reclaim resources | P0 |
| FR-17 | Retry failed attempts with exponential backoff | P0 |
| FR-18 | Track token usage across attempts | P1 |
| FR-19 | Support per-state concurrency limits | P1 |

### 5.5 Dashboard & API

| ID | Requirement | Priority |
|----|-------------|----------|
| FR-20 | Expose JSON API for state snapshot | P1 |
| FR-21 | Provide live HTML dashboard | P2 |
| FR-22 | Support manual refresh | P1 |
| FR-23 | Query individual issue status | P1 |

## 6. Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR-01 | Startup time (cold) | < 5 seconds |
| NFR-02 | Memory footprint | < 512 MB |
| NFR-03 | Concurrent workspaces | 10+ |
| NFR-04 | Test coverage | > 80% line coverage |
| NFR-05 | License | MIT |

## 7. Architecture Overview

```
┌─────────────────────────────────────────────────────┐
│                    koncerto-app                      │
│  (Spring Boot entry point, bean wiring, CLI runner) │
└──────────────────────┬──────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
        ▼              ▼              ▼
┌──────────────┐ ┌──────────┐ ┌──────────────┐
│ Orchestrator │ │ Dashboard│ │   CLI Runner │
│ (poll loop)  │ │ (REST)   │ │  (args)      │
└──────┬───────┘ └──────────┘ └──────────────┘
       │
       ├──────────────┬──────────────┐
       ▼              ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────────┐
│ Linear   │  │ Agent    │  │ Workspace    │
│ Client   │  │ Runner   │  │ Manager      │
└──────────┘  └──────────┘  └──────────────┘
       │              │              │
       ▼              ▼              ▼
┌──────────┐  ┌──────────┐  ┌──────────────┐
│ GraphQL  │  │ Codex    │  │ Hook         │
│ Client   │  │ Subproc  │  │ Executor     │
└──────────┘  └──────────┘  └──────────────┘
```

## 8. Module Dependency Graph

```
koncerto-core ← (no deps)
koncerto-logging ← core
koncerto-workflow ← core
koncerto-workspace ← core, logging
koncerto-linear ← core
koncerto-agent ← core, logging, workflow, workspace
koncerto-orchestrator ← core, logging, workflow, workspace, agent, linear
koncerto-dashboard ← core, orchestrator
koncerto-app ← all modules
```

## 9. Configuration Model

Koncerto reads configuration from a `WORKFLOW.md` file with YAML front matter:

```yaml
---
tracker:
  kind: linear
  api_key: $LINEAR_API_KEY
  project_slug: MYPROJECT
  active_states: [Todo, In Progress]
  terminal_states: [Done, Cancelled]
polling:
  interval_ms: 30000
workspace:
  root: /tmp/koncerto_workspaces
hooks:
  after_create: echo "workspace ready"
  before_run: npm install
agent:
  max_concurrent_agents: 5
  max_turns: 20
  max_retry_backoff_ms: 300000
codex:
  command: codex app-server
  turn_timeout_ms: 3600000
  stall_timeout_ms: 300000
---

You are working on {{ issue.identifier }}: {{ issue.title }}
```

## 10. API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/` | Live HTML dashboard |
| GET | `/api/v1/state` | JSON state snapshot |
| GET | `/api/v1/{identifier}` | Issue details by identifier |
| POST | `/api/v1/refresh` | Trigger manual refresh |

## 11. Out of Scope

- Web UI for configuration editing
- Multi-project support in single instance
- Agent-to-agent communication
- Custom agent runtime (only Codex)
- Persistent audit logging

## 12. Open Questions

| Question | Answer | Date |
|----------|--------|------|
| Should we support GitHub Issues? | Post-v1 | TBD |
| Should we support custom agent commands? | Yes, via `codex.command` | 2026-06-08 |

## 13. Appendices

### Appendix A: Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `LINEAR_API_KEY` | Linear API key | — |
| `KONCERTO_WORKFLOW_PATH` | Path to workflow file | `./WORKFLOW.md` |
| `KONCERTO_LOGS_ROOT` | Directory for log files | (stderr only) |
| `KONCERTO_WORKSPACE_ROOT` | Root dir for agent workspaces | `/tmp/symphony_workspaces` |
| `KONCERTO_WEB_TYPE` | `reactive` for HTTP dashboard, `none` for headless | `none` |

### Appendix B: Glossary

| Term | Definition |
|------|------------|
| **Workspace** | Isolated directory for an agent working on a specific issue |
| **Turn** | Single agent execution cycle (prompt → response) |
| **Attempt** | Complete execution of an issue (may include multiple turns) |
| **Reconciliation** | Process of checking running issues against tracker state |
| **Backoff** | Increasing delay between retry attempts |
