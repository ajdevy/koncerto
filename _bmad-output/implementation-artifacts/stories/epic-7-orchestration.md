# Epic 7: Orchestration

**Story Points:** 21  
**Priority:** P0  
**Status:** Complete  

---

## Story 7.1: RuntimeState

**ID:** 7.1  
**Title:** RuntimeState  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to track runtime state of the orchestrator
- **So that** running tasks, retries, and totals are centrally managed

### Acceptance Criteria
- [ ] RunningEntry data class with issue, thread, turn info
- [ ] RetryEntry data class with attempt and due time
- [ ] CodexTotals for token usage aggregation
- [ ] ConcurrentHashMap for running and retry entries
- [ ] Thread-safe claimed and completed sets
- [ ] availableSlots() calculation
- [ ] Unit tests cover all cases

### Technical Notes
- Use ConcurrentHashMap for thread safety
- Use Mutex for complex operations
- Volatile fields for configuration values
- Calculate available slots dynamically

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/RuntimeState.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/RuntimeStateTest.kt`

---

## Story 7.2: Orchestrator Core Loop

**ID:** 7.2  
**Title:** Orchestrator Core Loop  
**Points:** 8  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** an orchestrator that manages agent execution
- **So that** issues are processed continuously and reliably

### Acceptance Criteria
- [ ] start() launches coroutine scope with polling loop
- [ ] stop() cancels the loop
- [ ] tick() runs reconcile, preflight, fetchAndDispatch
- [ ] Poll interval configurable via RuntimeState
- [ ] Handle tick failures gracefully
- [ ] Unit tests cover all cases

### Technical Notes
- Use CoroutineScope for lifecycle
- Delay between ticks
- Launch event collection in separate coroutine
- Log tick failures

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

---

## Story 7.3: Reconciliation

**ID:** 7.3  
**Title:** Reconciliation  
**Points:** 4  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to reconcile running issues with tracker state
- **So that** completed issues are cleaned up automatically

### Acceptance Criteria
- [ ] Fetch issue states from Linear for running issues
- [ ] Stop tracking terminal state issues
- [ ] Stop tracking non-active state issues
- [ ] Remove workspace for stopped issues
- [ ] Log reconciliation actions
- [ ] Handle fetch failures gracefully
- [ ] Unit tests cover all cases

### Technical Notes
- Use LinearClient for state fetching
- Compare against config terminal/active states
- Clean up running and claimed sets
- Log warnings for failures

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

---

## Story 7.4: Dispatch and Retry

**ID:** 7.4  
**Title:** Dispatch and Retry  
**Points:** 6  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to dispatch issues to agents with retry logic
- **So that** failed attempts are retried with backoff

### Acceptance Criteria
- [ ] fetchAndDispatch() filters and sorts candidates
- [ ] Dispatch respects maxConcurrentAgents limit
- [ ] Dispatch respects per-state limits
- [ ] Filter by required labels
- [ ] Block todo issues with unresolved blockers
- [ ] scheduleRetry() with exponential backoff
- [ ] Log dispatch and retry actions
- [ ] Unit tests cover all cases

### Technical Notes
- Sort by priority, then createdAt, then identifier
- Exponential backoff: 10s * 2^attempt
- Cap at maxRetryBackoffMs
- Track retry attempts in RuntimeState

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

---

## Story 7.5: Preflight and Event Handling

**ID:** 7.5  
**Title:** Preflight and Event Handling  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** preflight checks and event handling
- **So that** configuration issues are logged early

### Acceptance Criteria
- [ ] runPreflight() validates required config fields
- [ ] Log warning for missing configuration
- [ ] handleAgentEvent() processes turn completed events
- [ ] Aggregate token usage in RuntimeState
- [ ] Unit tests cover all cases

### Technical Notes
- Check tracker config completeness
- Check codex command presence
- Update codexTotals on TurnCompleted
- Log warnings for invalid config

### Implementation
- File: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`
