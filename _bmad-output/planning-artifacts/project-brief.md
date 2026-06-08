# Project Brief: Koncerto

**Date:** 2026-06-08  
**Analyst:** Alex the Analyst  
**Status:** Approved  

---

## Executive Summary

Koncerto is a Kotlin/Spring Boot orchestration service that automates software development workflows by connecting project trackers (Linear) with AI coding agents (Codex). It eliminates manual issue-to-code handoff by automatically discovering, dispatching, and managing coding tasks.

## Problem Statement

Software teams using Linear for issue tracking and AI agents for code generation lack a unified orchestration layer that:
- Automatically picks up issues when they enter active states
- Manages concurrent agent execution with isolation
- Handles failures with retry logic
- Provides visibility into agent activity
- Enforces workflow conventions via configurable templates

## Target Users

### Primary: Engineering Lead
- **Role:** Manages development team and workflows
- **Needs:** Automate routine tasks, monitor agent activity, ensure quality
- **Pain Points:** Manual issue assignment, no visibility into agent work, inconsistent workflows

### Secondary: Developer
- **Role:** Writes code and implements features
- **Needs:** Focus on complex tasks, reduce context switching
- **Pain Points:** Repetitive bug fixes, manual setup, unclear requirements

### Tertiary: DevOps Engineer
- **Role:** Manages infrastructure and deployment
- **Needs:** Reliable automation, observability, minimal maintenance
- **Pain Points:** Unreliable automation, poor logging, difficult debugging

## Product Vision

**Vision:** A reliable, observable automation layer that connects issue trackers with AI agents, enabling teams to focus on high-value work while routine tasks are handled automatically.

**Differentiators:**
1. **Isolation:** Each issue gets its own workspace with lifecycle hooks
2. **Observability:** Real-time dashboard and structured logging
3. **Configurability:** YAML-based workflow definitions with Liquid templates
4. **Reliability:** Exponential backoff retry, concurrency limits, stall detection

## Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| Time from issue to PR | < 15 minutes | Linear API timestamps |
| Successful completion rate | > 90% | Completed / Total dispatched |
| Mean time to detect failure | < 2 minutes | Dashboard + alerts |
| Agent isolation | 100% | Zero cross-workspace interference |
| Configurability | < 30 minutes | Time to add new workflow |

## Scope

### In Scope (v1.0)
- Linear integration (polling, issue mapping)
- Codex agent runtime (JSON-RPC, subprocess management)
- Workspace isolation with lifecycle hooks
- Retry logic with exponential backoff
- Real-time dashboard and REST API
- YAML-based workflow configuration

### Out of Scope (v1.0)
- GitHub Issues integration
- Custom agent runtimes
- Web UI for configuration
- Persistent audit logging
- Multi-project support

### Future Considerations
- GitHub/GitLab integration
- Additional agent runtimes
- Database for metrics and audit trail
- OAuth authentication for dashboard

## Constraints

| Constraint | Impact | Mitigation |
|------------|--------|------------|
| Linear API rate limits | Polling frequency | Configurable intervals, efficient queries |
| Codex subprocess overhead | Memory usage | Workspace cleanup, concurrency limits |
| Kotlin/JVM startup time | Cold start | Spring Boot optimization, minimal dependencies |

## Assumptions

1. Linear API is available and stable
2. Codex CLI is installed and functional
3. Users have basic Kotlin/Gradle knowledge
4. Internal deployment (no public internet exposure)

## Risks

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Linear API changes | Medium | High | Versioned API client, error handling |
| Codex subprocess crashes | Medium | Medium | Retry logic, stall detection |
| Workspace disk exhaustion | Low | Medium | Cleanup hooks, monitoring |

## Timeline

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| Analysis | 1 week | Project Brief (this document) |
| Planning | 1 week | PRD, Epics, Stories |
| Solutioning | 1 week | Architecture, Technical Design |
| Implementation | 4 weeks | Working product |
| Total | 7 weeks | v1.0 release |

## stakeholders

| Stakeholder | Role | Interest |
|-------------|------|----------|
| Engineering Lead | Decision Maker | Workflow automation, monitoring |
| Development Team | End Users | Reduced manual work |
| DevOps | Operator | Reliability, observability |

## Open Questions

1. Should we support multiple agent runtimes in v1?
2. What's the expected number of concurrent issues?
3. Do we need authentication for the dashboard?

## Next Steps

1. PM creates PRD with detailed requirements
2. Architect designs system architecture
3. Team reviews and approves plan
4. Implementation begins
