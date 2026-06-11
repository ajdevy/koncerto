# Epic 14: Scalable Multi-Project

**Story Points:** 24
**Priority:** P1
**Status:** Planned

---

## Story 14.1: Tenant Context Model

**ID:** 14.1
**Title:** Tenant Context Model
**Points:** 3
**Priority:** P0

### User Story
- **As a** platform engineer
- **I want** a strongly-typed tenant context model that isolates projects by identity
- **So that** multi-tenant workloads are safely partitioned at runtime

### Acceptance Criteria
- [ ] TenantId value class wrapping UUID or string
- [ ] TenantContext data class with tenantId, projectSlug, tier, quotaProfile fields
- [ ] TenantResolver interface with resolveTenant(projectSlug): TenantContext method
- [ ] Configurable tenant resolution via project config (tenant.tier, tenant.quotaProfile)
- [ ] Unit tests for tenant resolution from config, missing tenant config, invalid tenant ID
- [ ] Integration test demonstrating two tenants with isolated configs

### Technical Notes
- TenantId should be a `@JvmInline value class` for zero-cost abstraction
- TenantResolver injected into DispatchService and Orchestrator
- Tenant config is optional (backward compatible for single-tenant setups)
- Extend ProjectConfig with an optional `tenant: TenantConfig` block

### Implementation
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/tenant/TenantId.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/tenant/TenantContext.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/tenant/TenantResolver.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/tenant/TenantResolverTest.kt`

---

## Story 14.2: Tenant Context Propagation

**ID:** 14.2
**Title:** Tenant Context Propagation
**Points:** 5
**Priority:** P1

### User Story
- **As a** developer
- **I want** tenant context to propagate through the agent runtime and orchestrator automatically
- **So that** all downstream operations are scoped to the correct tenant

### Acceptance Criteria
- [ ] TenantContext propagated through DispatchService when dispatching agents
- [ ] TenantContext passed to AgentRuntime.execute() via AgentContext
- [ ] Tenant-scoped workspace directories created under tenant subdirectories
- [ ] Tenant-scoped Linear client calls include tenant identifier in log context
- [ ] Structured logging includes tenantId in all log entries for multi-tenant deployments
- [ ] Correlation ID chains across tenant-boundary calls (workflow → agent → callback)
- [ ] Unit tests: tenant propagation through agent dispatch, workspace isolation
- [ ] Integration test: two tenants running concurrent workflows without interference

### Technical Notes
- Add tenantId field to AgentContext and RuntimeState
- WorkspaceManager resolves root path per tenant: `{workspaceRoot}/{tenantId}/{projectSlug}/`
- StructuredLogger adds `tenantId` key to MDC
- No tenant context leaks across coroutine boundaries — use CoroutineContext element

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/RuntimeState.kt`
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRuntime.kt`
- Modify: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/WorkspaceManager.kt`
- Modify: `koncerto-logging/src/main/kotlin/com/anomaly/koncerto/logging/StructuredLogger.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

---

## Story 14.3: Resource Quotas Per Project

**ID:** 14.3
**Title:** Resource Quotas Per Project
**Points:** 3
**Priority:** P1

### User Story
- **As a** platform admin
- **I want** to configure per-project resource quotas (concurrent agents, rate limits, workspace storage)
- **So that** no single tenant can exhaust shared resources

### Acceptance Criteria
- [ ] QuotaConfig model in ProjectConfig with maxConcurrentAgents, maxRateLimit, maxWorkspaceStorageMB
- [ ] QuotaEnforcer service that checks quota before dispatching new agents
- [ ] Quota exceeded → workflow queued with "quota_exceeded" status
- [ ] Quota released when agent completes or fails (via transitionOnComplete / onFailed paths)
- [ ] Quota metrics exported to Prometheus (koncerto_quota_remaining, koncerto_quota_exceeded_total)
- [ ] Unit tests: quota enforcement, quota release, concurrent quota exceeded, config parsing
- [ ] Integration test: exceeding quota then releasing via completion

### Technical Notes
- QuotaEnforcer uses in-memory counter (atomic) per tenant
- For clustered deployments, quota state must be externalized (Redis or DB) — metadata store story
- Semantic: maxConcurrentAgents is a hard ceiling; exceeded requests are queued, not rejected
- Extend ProjectConfig with optional `quota: QuotaConfig`

### Implementation
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/quota/QuotaConfig.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/quota/QuotaEnforcer.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Modify: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/PrometheusMetricsBinder.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/quota/QuotaEnforcerTest.kt`

---

## Story 14.4: Cross-Project Workflow Chaining

**ID:** 14.4
**Title:** Cross-Project Workflow Chaining
**Points:** 8
**Priority:** P1

### User Story
- **As an** operator
- **I want** to chain workflows across projects, so that dependent work in separate projects is automatically triggered when a source issue completes
- **So that** multi-project delivery pipelines are automated

### Acceptance Criteria
- [ ] CrossProjectFollowUpConfig with targetProjectSlug, titleTemplate, descriptionTemplate, linkType fields
- [ ] crossProjectFollowUp field added to StageAgentConfig (nullable)
- [ ] CrossProjectChainer service resolves target tenant from targetProjectSlug
- [ ] CrossProjectChainer creates issue in target project via target project's LinearClient
- [ ] CrossProjectChainer creates cross-project link if linkType is set
- [ ] Tenant context is propagated to the follow-up issue's workflow
- [ ] Cross-project chain failures are logged but do not block the source transition
- [ ] Max chain depth enforced (configurable, default 3) to prevent infinite loops
- [ ] Cycle detection: same source+target already linked → skip duplicate chain
- [ ] Unit tests: cross-project chain creation, depth enforcement, cycle detection, failure handling

### Technical Notes
- CrossProjectChainer resolves the target ProjectConfig from the global config registry
- Target project's LinearClient is obtained via LinearClientProvider (tenant-aware)
- Cycle detection uses in-memory set of {sourceId, targetProjectSlug} tuples per chain
- Chain depth tracked via X-Koncerto-Chain-Depth header or context field
- Follow-up issue carries metadata pointing back to source project

### Implementation
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/CrossProjectChainer.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/CrossProjectFollowUpConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/CrossProjectChainerTest.kt`

---

## Story 14.5: Admin Dashboard for Multi-Project Management

**ID:** 14.5
**Title:** Admin Dashboard for Multi-Project Management
**Points:** 5
**Priority:** P2

### User Story
- **As a** platform admin
- **I want** a dashboard view showing all projects, their health, tenant assignments, and current usage
- **So that** I can monitor and manage the multi-tenant deployment from a single pane

### Acceptance Criteria
- [ ] GET /api/v1/admin/projects endpoint listing all registered projects with tenant info
- [ ] GET /api/v1/admin/projects/{projectSlug} endpoint with detailed project status
- [ ] GET /api/v1/admin/tenants endpoint listing tenants with aggregate metrics
- [ ] GET /api/v1/admin/quotas endpoint showing current quota usage per project
- [ ] Dashboard UI page (Thymeleaf or Htmx) listing projects with health indicators (green/yellow/red)
- [ ] Dashboard shows: active agents, queue depth, quota usage %, last activity timestamp
- [ ] Admin endpoints require X-Admin-Key header for authentication
- [ ] Unit tests for all admin endpoints
- [ ] Integration test: full admin dashboard data flow

### Technical Notes
- Admin auth via shared secret (X-Admin-Key header), configurable in ServiceConfig.security
- Project registry holds all loaded ProjectConfig instances indexed by projectSlug
- Aggregate metrics drawn from MetricsRepository and QuotaEnforcer
- Dashboard UI uses server-rendered HTML with Htmx for live updates (no SPA required)

### Implementation
- Create: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/admin/AdminController.kt`
- Create: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/admin/ProjectRegistry.kt`
- Create: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/admin/AdminDashboardView.kt` (or HTML template)
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/admin/AdminControllerTest.kt`
