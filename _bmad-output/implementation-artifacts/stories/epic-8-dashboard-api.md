# Epic 8: Dashboard API

**Story Points:** 8  
**Priority:** P1  
**Status:** Complete  

---

## Story 8.1: DashboardController

**ID:** 8.1  
**Title:** DashboardController  
**Points:** 2  
**Priority:** P1  

### User Story
- **As an** operator
- **I want** a web dashboard to view orchestrator status
- **So that** I can monitor agent activity in real-time

### Acceptance Criteria
- [x] Serve HTML at root path "/"
- [x] Load dashboard.html from classpath
- [x] Return Mono<String> for reactive handling
- [x] Unit tests cover endpoint

### Technical Notes
- Use Spring WebFlux
- ClassPathResource for template loading
- TEXT_HTML_VALUE content type

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/DashboardController.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/DashboardControllerTest.kt`

---

## Story 8.2: ApiV1Controller State Endpoint

**ID:** 8.2  
**Title:** ApiV1Controller State Endpoint  
**Points:** 3  
**Priority:** P1  

### User Story
- **As an** operator
- **I want** a REST API to query orchestrator state
- **So that** I can build monitoring tools and integrations

### Acceptance Criteria
- [x] GET /api/v1/state returns state snapshot
- [x] StateSnapshot includes running, retrying, totals, rateLimits
- [x] RunningRow includes issue and token info
- [x] RetryingRow includes attempt and error info
- [x] Totals aggregates all token usage
- [x] Return JSON response
- [x] Unit tests cover endpoint

### Technical Notes
- Use Spring WebFlux
- Serializable data classes for JSON
- Map from RuntimeState to DTOs
- Mono.just() for sync responses

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`

---

## Story 8.3: ApiV1Controller Lookup Endpoint

**ID:** 8.3  
**Title:** ApiV1Controller Lookup Endpoint  
**Points:** 2  
**Priority:** P1  

### User Story
- **As an** operator
- **I want** to look up running state by issue identifier
- **So that** I can quickly check a specific issue's status

### Acceptance Criteria
- [x] GET /api/v1/{identifier} returns issue state
- [x] Return issueId, threadId, turnId, turnCount
- [x] Return error map if not found
- [x] Unit tests cover both cases

### Technical Notes
- Use PathVariable for identifier
- Filter running entries by identifier
- Return simple map for response
- Handle not found gracefully

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`

---

## Story 8.4: ApiV1Controller Refresh Endpoint

**ID:** 8.4  
**Title:** ApiV1Controller Refresh Endpoint  
**Points:** 1  
**Priority:** P1  

### User Story
- **As an** operator
- **I want** to trigger a state refresh
- **So that** I can force immediate reconciliation

### Acceptance Criteria
- [x] POST /api/v1/refresh returns ok status
- [x] Unit tests cover endpoint

### Technical Notes
- Use PostMapping
- Return status map
- Placeholder for future implementation

### Implementation
- File: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Tests: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`
