# Epic 5: Linear Integration

**Story Points:** 13  
**Priority:** P0  
**Status:** Complete  

---

## Story 5.1: LinearError

**ID:** 5.1  
**Title:** LinearError  
**Points:** 1  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** typed error classes for Linear API failures
- **So that** errors are explicitly handled and categorized

### Acceptance Criteria
- [ ] Sealed class hierarchy for error types
- [ ] MissingApiKey, MissingProjectSlug errors
- [ ] Request, Status, GraphQlErrors errors
- [ ] UnknownPayload, MissingEndCursor errors
- [ ] Unit tests cover all error types

### Technical Notes
- Use sealed class for type safety
- Include error message and optional cause
- Prefix error codes for logging

### Implementation
- File: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearError.kt`
- Tests: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearErrorTest.kt`

---

## Story 5.2: LinearGraphQLClient

**ID:** 5.2  
**Title:** LinearGraphQLClient  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a GraphQL client for Linear API
- **So that** I can execute queries against the Linear API

### Acceptance Criteria
- [ ] Execute GraphQL queries with variables
- [ ] Include Authorization header with API key
- [ ] Handle timeout via Duration
- [ ] Throw LinearError for missing API key
- [ ] Throw LinearError for GraphQL errors in response
- [ ] Unit tests cover all cases

### Technical Notes
- Use Spring WebClient for HTTP
- JSON serialization with kotlinx.serialization
- Configurable timeout (default 30s)
- Run on Dispatchers.IO

### Implementation
- File: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt`
- Tests: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClientTest.kt`

---

## Story 5.3: IssueMapper

**ID:** 5.3  
**Title:** IssueMapper  
**Points:** 3  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to map Linear API responses to Issue model
- **So that** raw JSON is converted to strongly typed objects

### Acceptance Criteria
- [ ] Map all Issue fields from GraphQL response
- [ ] Extract nested state name
- [ ] Map labels to lowercase strings
- [ ] Map blockedBy relations to BlockerRef list
- [ ] Parse Instant timestamps
- [ ] Handle missing optional fields
- [ ] Unit tests cover all cases

### Technical Notes
- Use JsonObject extension functions
- Handle null/missing fields gracefully
- Filter empty labels
- Parse ISO-8601 timestamps

### Implementation
- File: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/IssueMapper.kt`
- Tests: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/IssueMapperTest.kt`

---

## Story 5.4: LinearClient

**ID:** 5.4  
**Title:** LinearClient  
**Points:** 6  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a high-level client for Linear operations
- **So that** I can fetch issues and states with pagination

### Acceptance Criteria
- [ ] fetchCandidateIssues() with pagination support
- [ ] fetchIssuesByStates() for state queries
- [ ] fetchIssueStatesByIds() for state checks
- [ ] Handle pagination cursors correctly
- [ ] Throw error for missing end cursor
- [ ] Unit tests cover all cases

### Technical Notes
- Implement LinearClient interface
- Use GraphQL queries for each operation
- Handle empty input lists early
- Extract pageInfo for pagination

### Implementation
- File: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt`
- Tests: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt`
