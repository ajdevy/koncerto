# Beyond-Scope Features Documentation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Document all beyond-scope features using BMAD method (proper epics, stories, acceptance criteria) and implement production hardening for agent robustness.

**Architecture:** Use existing BMAD artifact structure (e.g., `_bmad-output/implementation-artifacts/stories/`) for documenting beyond-scope work as "implemented epics" with proper BMAD documentation. For production hardening, add robustness layers (error handling, retries, fallback) around agent execution pipeline (Orchestration -> DefaultAgentRunner -> Subprocess management).

**Tech Stack:** Existing koncerto tech stack (Kotlin, Spring Boot, coroutines, existing conventions)

---

### Task 1: Document Beyond-Scope Features with BMAD Method

**Files:**
- Modify: `_bmad-output/implementation-artifacts/stories/epic-13-beyond-scope-features-summary.md`  
- Create: `_bmad-output/implementation-artifacts/stories/epic-13-beyond-scope-features.md`

**Breakdown tasks:**

- [ ] **Task 1: Define epic scope summary**

Create epic summary document that lists all beyond-scope features with:

- **Title:** Epic 13: Beyond-Scope Enhancements
- **Story Points:** 25 (estimated)
- **Priority:** P2 (secondary to core epics)
- **Status:** Complete
- **Links to implementation commits**: Reference all commits implementing each feature

Content sections:
1. Overview: What was built beyond original scope
2. Feature breakdown: List of all beyond-scope features
3. Implementation notes: Challenges, lessons learned, trade-offs

- [ ] **Task 2: Create individual epic files for each major beyond-scope feature**

For each major beyond-scope feature, create:

- `epic-##-feature-name.md`
- With proper BMAD structure: Overview, Stories, Implementation details
- Mark all stories as complete with acceptance criteria
- Link to implementation source code and commits

Major features:
1. Dockerfile + docker-compose deployment
2. Prometheus metrics and monitoring
3. Conventional commits system
4. Dashboard authentication
5. Config editor UI
6. Visual dependency graph
7. Agent-to-agent messaging
8. Automated PR creation
9. GitHub Issues tracker bridge
10. E2E CI and Docker publish
11. Test coverage expansion

- [ ] **Task 3: Document technical decisions and lessons learned**

Create technical notes document covering:

- Circular dependency issues and resolution
- Architecture challenges and solutions
- Testing strategies and challenges
- Performance considerations
- Security and authentication decisions
- Future maintenance considerations

- [ ] **Task 4: Generate design documentation references**

For each major feature, create design documentation:

- `docs/superpowers/specs/2026-06-10-feature-name-design.md` (if missing)
- Reference existing design docs where available
- Link design specs to implementation

### Task 2: Production Hardening - Agent Robustness

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/agent/RateLimitConfig.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/agent/RateLimitProvider.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/DefaultAgentRunner.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`

**Breakdown tasks:**

- [ ] **Task 5: Add rate limit configuration to ProjectConfig**

```kotlin
// Add to ProjectConfig
class AgentProjectConfig(
    // ... existing fields
    val apiRateLimits: Map<String, RateLimitConfig>? = null,
    val agentRateLimits: Map<String, RateLimitConfig>? = null,
)
```

RateLimitConfig class:
```kotlin
class RateLimitConfig(
    val requestsPerMinute: Int,
    val requestsPerHour: Int,
    val burstCapacity: Int,
    val backoffMs: Int,
)
```

- [ ] **Task 6: Implement rate limit tracking in RateLimitProvider**

Track API calls to providers:

- `LinearGraphQLClient` rate limits (requests/min, requests/hour)
- `CodeX`/`AsyncAPI` rate limits from different providers
- Local agent subprocess execution rate limits
- Track calls, enforce limits, apply exponential backoff

- [ ] **Task 7: Modify DefaultAgentRunner for robustness**

Add error handling for:

1. **Agent startup failures**
2. **Process hang/stall detection**
3. **Output parsing failures**
4. **Non-zero exit codes with fallback logic**
5. **Timeout handling for different agent types**

Implement:

- Retry logic for transient failures
- Fallback agent configurations
- Circuit breaker patterns
- Better logging and health reporting

- [ ] **Task 8: Enhance DispatchService for provider error recovery**

Add:

1. **Provider circuit breakers** for Linear and agent APIs
2. **Exponential backoff with jitter**
3. **Automatic fallback providers**
4. **Graceful degradation when all providers fail**

- [ ] **Task 9: Add health checking for agent subprocesses**

Implement:

- Health check endpoints for agent runners
- Monitoring of agent availability
- Automatic restart of failed agents
- Load balancing between agent instances

- [ ] **Task 10: Add observability for rate limits and errors**

Add metrics and logging for:

- API call tracking and rate limit alerts
- Agent startup/shutdown and recovery events
- Error patterns and frequency analysis
- System health indicators

### Task 3: Next Phase Planning (Phase 5 and 6)

**Files:**
- Create: `_bmad-output/implementation-artifacts/stories/epic-14-scalable-multi-project.md`
- Create: `_bmad-output/implementation-artifacts/stories/epic-15-cloud-native-deploy.md`
- Create: `_bmad-output/implementation-artifacts/sprint-status.yaml` (for Phase 5)

**Breakdown tasks:**

- [ ] **Task 11: Define Phase 5: Scalable Multi-Project**

Plan for:

- Multi-tenant architecture
- Project isolation
- Cross-project workflows
- Resource allocation and prioritization
- Enterprise-grade deployment patterns

- [ ] **Task 12: Define Phase 6: Cloud-Native**

Plan for:

- Kubernetes deployment
- Auto-scaling
- Zero-downtime deployments
- Observability-as-code
- Infrastructure as code

- [ ] **Task 13: Create Phase 5 sprint status tracking**

Set up sprint status management for Phase 5.

