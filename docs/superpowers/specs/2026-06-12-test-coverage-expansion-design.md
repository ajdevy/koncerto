# Test Coverage Expansion — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Targeted expansion of test coverage across all beyond-scope features: Docker deployment, rate limiting, circuit breakers, notifications, agent-to-agent messaging, automated PR creation, dashboard authentication, and the GitHub Issues bridge. Adds 30+ new tests across multiple modules.

## Motivation

Beyond-scope features were implemented rapidly. Many lack dedicated test coverage. Without tests, regressions in rate limiting, authentication, and inter-agent messaging could silently break production behavior. This expansion ensures each feature is validated at the unit, integration, and (where applicable) E2E level.

## Test Plan

### Module: koncerto-core (8 new tests)

| Test | Scope |
|------|-------|
| `TokenBucketRateLimiterTest` | Acquire, exhaustion, refill, burst |
| `CircuitBreakerTest` | Closed → open → half-open cycle |
| `ServiceConfigAuthTest` | Auth config parsing |
| `ServiceConfigMessagingTest` | Agent messaging config parsing |
| `ServiceConfigPRTest` | PR creation config parsing |

### Module: koncerto-linear (5 new tests)

| Test | Scope |
|------|-------|
| `GitHubIssuesClientTest` | Fetch, update, error handling |
| `RateLimitedLinearClientTest` | Rate limiter + circuit breaker decoration |
| `LinearClientFallbackTest` | Primary failure → fallback activation |

### Module: koncerto-orchestrator (10 new tests)

| Test | Scope |
|------|-------|
| `DispatchServiceAuthTest` | Authenticated vs unauthenticated dispatch |
| `DispatchServicePRTest` | PR creation triggered on completion |
| `AgentMessagingTest` | Message send/route/ack cycle in orchestration |
| `OrchestratorMultiTrackerTest` | Mixed Linear + GitHub projects |
| `OrchestratorDockerHealthTest` | Health check in container context |

### Module: koncerto-dashboard (5 new tests)

| Test | Scope |
|------|-------|
| `ApiV1ConfigControllerTest` | Config load/save/validate endpoints |
| `AuthFilterIntegrationTest` | API key + JWT filter chain |

### Module: koncerto-notifications (5 new tests)

| Test | Scope |
|------|-------|
| `WebhookNotifierTest` | HTTP POST delivery |
| `FakeNotifierTest` | Event recording and assertion |

### Integration

- Docker Compose smoke test — `docker compose up --wait`, verify health endpoint
- E2E auth test — agent run with authenticated dashboard access

## Testing Strategy

- Unit tests: JUnit 5 + Turbine for Flow assertions
- Integration tests: WireMock for external HTTP services
- E2E tests: Tagged `@Tag("e2e")`, excluded from default build
- Target: 85%+ line coverage on new code, 70%+ overall for affected modules

## Open Questions

- Should we add mutation testing (Pitest) to validate test quality, or rely on coverage thresholds?
- Do we need a performance/load test for rate limiters and circuit breakers under concurrent agent dispatch?
