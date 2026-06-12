# Epic 13.09: GitHub Issues Bridge

**Story Points:** 8  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.09.1: TrackerClient Abstraction

**ID:** 13.09.1  
**Title:** TrackerClient Abstraction  
**Points:** 3  
**Priority:** P2  

### User Story
- **As a** Koncerto user
- **I want** a `TrackerClient` abstraction interface
- **So that** the system supports multiple issue trackers

### Acceptance Criteria
- [x] `TrackerClient` interface with create, update, get, search, and list operations
- [x] Common models for issue, project, and user across providers
- [x] Unified error handling for all tracker providers
- [x] Provider-agnostic pagination support
- [x] Service loader or config-based provider registration

### Technical Notes
- Interface designed for extensibility with new providers
- Domain models in core module for reuse
- Error types mapped to common `DataError` hierarchy
- Provider metadata for display and configuration

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/tracker/TrackerClient.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/tracker/TrackerClientTest.kt`

---

## Story 13.09.2: GitHubIssuesClient Implementation

**ID:** 13.09.2  
**Title:** GitHubIssuesClient Implementation  
**Points:** 3  
**Priority:** P2  

### User Story
- **As a** Koncerto user
- **I want** a `GitHubIssuesClient` implementing the TrackerClient interface
- **So that** I can use GitHub Issues instead of Linear

### Acceptance Criteria
- [x] `GitHubIssuesClient` implements `TrackerClient` interface
- [x] Uses GitHub REST API v3 for issue operations
- [x] Authentication via GitHub personal access token
- [x] Issue CRUD: create, update, get, close, reopen
- [x] Pagination support for listing issues
- [x] Rate limit handling with retry and backoff

### Technical Notes
- Ktor HttpClient for API calls
- GitHub API path: `/repos/{owner}/{repo}/issues`
- Token-based auth via `Authorization: Bearer` header
- GraphQL option for advanced queries if needed

### Implementation
- File: `koncerto-github/src/main/kotlin/com/anomaly/koncerto/github/GitHubIssuesClient.kt`
- Tests: `koncerto-github/src/test/kotlin/com/anomaly/koncerto/github/GitHubIssuesClientTest.kt`

---

## Story 13.09.3: Multi-Tracker Configuration

**ID:** 13.09.3  
**Title:** Multi-Tracker Configuration  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** Koncerto user
- **I want** multi-tracker configuration support
- **So that** different projects can use different issue trackers

### Acceptance Criteria
- [x] Configuration supports multiple tracker providers simultaneously
- [x] Per-project tracker assignment
- [x] Unified API that delegates to the correct provider
- [x] Fallback to Linear when GitHub is unavailable
- [x] Provider health check for availability monitoring

### Technical Notes
- Tracker registry maps project identifiers to providers
- Unified API facade delegates to correct `TrackerClient`
- Fallback chain: primary -> secondary -> error
- Health checks run on configurable interval

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/tracker/TrackerRegistry.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/tracker/TrackerRegistryTest.kt`
