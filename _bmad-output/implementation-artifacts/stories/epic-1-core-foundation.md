# Epic 1: Core Foundation

**Story Points:** 8  
**Priority:** P0  
**Status:** Complete  

---

## Story 1.1: Result Wrapper

**ID:** 1.1  
**Title:** Result Wrapper  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a Result wrapper
- **So that** errors are explicitly handled

### Acceptance Criteria
- [ ] Result<T, E> supports map, onSuccess, onFailure, getOrNull
- [ ] Unit tests cover all methods
- [ ] Documentation explains usage

### Technical Notes
- Use sealed class for type safety
- Include extension functions for convenience
- Follow Kotlin coding conventions

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/result/Result.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/result/ResultTest.kt`

---

## Story 1.2: Issue Model

**ID:** 1.2  
**Title:** Issue Model  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** an Issue model
- **So that** tracker data is strongly typed

### Acceptance Criteria
- [ ] Issue data class with all Linear fields
- [ ] normalizedState property
- [ ] Unit tests

### Technical Notes
- Include all fields from Linear API
- Add computed properties for common operations
- Use data class for immutability

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/model/Issue.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/model/IssueTest.kt`

---

## Story 1.3: WorkflowDefinition

**ID:** 1.3  
**Title:** WorkflowDefinition  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** to parse workflow definitions
- **So that** config is separated from content

### Acceptance Criteria
- [ ] Parse YAML front matter from markdown
- [ ] Validate required fields
- [ ] Unit tests

### Technical Notes
- Use YAML parser for front matter
- Extract content after ---
- Handle missing fields gracefully

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/WorkflowDefinition.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/WorkflowDefinitionTest.kt`

---

## Story 1.4: ServiceConfig

**ID:** 1.4  
**Title:** ServiceConfig  
**Points:** 2  
**Priority:** P0  

### User Story
- **As a** developer
- **I want** a ServiceConfig
- **So that** runtime settings are centralized

### Acceptance Criteria
- [ ] Config with env resolution, path expansion
- [ ] Validation rules
- [ ] Unit tests

### Technical Notes
- Support environment variable references ($VAR)
- Expand ~ and $HOME in paths
- Validate required fields

### Implementation
- File: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`
