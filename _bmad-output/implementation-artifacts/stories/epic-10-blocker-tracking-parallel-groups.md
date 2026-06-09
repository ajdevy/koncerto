# Epic 10: Blocker State Tracking & Parallel Execution Groups

**Story Points:** 13  
**Priority:** P1  
**Status:** Planned

---

## Story 10.1: Blocker State Tracking in Reconciliation

**ID:** 10.1  
**Title:** Blocker State Tracking  
**Points:** 3  
**Priority:** P1

### User Story
- **As a** developer
- **I want** the orchestrator to track which issues are blocked and auto-unblock when blockers resolve
- **So that** the dispatch system always has an accurate picture of available work

### Acceptance Criteria
- [ ] reconcile() checks blocker states after state cleanup
- [ ] Issues whose blockers all reached terminal states are marked unblocked
- [ ] Log `unblocked` event with issue identifier
- [ ] RuntimeState.blocked set is correctly maintained (add/remove)
- [ ] Unit tests cover: single blocker resolves, multiple blockers resolve, no blockers, blocker not in candidate set
- [ ] Dashboard reflects current blocked state

### Technical Notes
- Extend reconcile() in Orchestrator.kt
- Scan running/claimed issues' blockedBy entries
- Compare blocker states against fetched issue state map
- Use existing fetchIssueStatesByIds batch call

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

---

## Story 10.2: Dependency Graph and Frontier Computation

**ID:** 10.2  
**Title:** Dependency Graph & Frontier  
**Points:** 5  
**Priority:** P1

### User Story
- **As a** developer
- **I want** the orchestrator to build a dependency graph from issue blockers and compute the dispatch frontier
- **So that** only unblocked issues are dispatched and parallelism is maximized

### Acceptance Criteria
- [ ] DependencyGraph data class with nodes, edges, frontier
- [ ] computeFrontier() builds graph from candidate issues
- [ ] Frontier contains issues with no unresolved blockers
- [ ] Blocker absent from candidates → treated as resolved
- [ ] Unlinked blocker (id=null) → treated as resolved
- [ ] Frontier sorted by priority, then createdAt, then identifier
- [ ] Unit tests cover: empty graph, chain, diamond, mixed blockers, all blocked

### Technical Notes
- New file: DependencyGraph.kt in orchestrator package
- Used by DispatchService.fetchAndDispatch()
- No extra API calls (uses candidates already fetched)

### Implementation
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraph.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DependencyGraphTest.kt`

---

## Story 10.3: Block-Aware Dispatch

**ID:** 10.3  
**Title:** Block-Aware Dispatch  
**Points:** 3  
**Priority:** P1

### User Story
- **As a** developer
- **I want** fetchAndDispatch() to use the dependency frontier instead of simple isBlockedForTodo()
- **So that** the system dispatches all available unblocked issues in priority order

### Acceptance Criteria
- [ ] fetchAndDispatch() builds DependencyGraph from candidates
- [ ] Dispatches from frontier instead of filtered list
- [ ] Respects maxConcurrentAgents and per-state limits
- [ ] Existing isBlockedForTodo() replaced by frontier logic
- [ ] Blocked issues tracked in state.blocked for dashboard visibility
- [ ] Unit tests cover: frontier dispatch, concurrency limits with blocking

### Technical Notes
- Replace isBlockedForTodo() with DependencyGraph.computeFrontier()
- Keep matchesRequiredLabels() as pre-filter
- Sort by frontier order (already sorted by priority)
- State.blocked set used for dashboard API

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

---

## Story 10.4: Dashboard Blocker Visibility

**ID:** 10.4  
**Title:** Dashboard Blocker Visibility  
**Points:** 2  
**Priority:** P2

### User Story
- **As an** operator
- **I want** the dashboard to show which issues are blocked and by what
- **So that** I can see the dependency chain at a glance

### Acceptance Criteria
- [ ] API exposes blocked issues with blocker identifiers
- [ ] Dashboard shows blocked badge/tag on blocked issues
- [ ] Dashboard shows blocker identifiers in issue detail
- [ ] Blocker-chain visualization (optional, future)

### Technical Notes
- Extend APIv1Controller to include blocked state
- Dashboard HTML template shows blocked status
- Blocker info from Issue.blockedBy field

### Implementation
- Modify: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Modify: `koncerto-dashboard/src/main/resources/templates/dashboard.html`
