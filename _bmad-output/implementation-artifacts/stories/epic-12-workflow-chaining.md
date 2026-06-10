# Epic 12: Workflow Chaining

**Story Points:** 10  
**Priority:** P2  
**Status:** Complete

---

## Story 12.1: FollowUp Config Model

**ID:** 12.1  
**Title:** FollowUp Config Model  
**Points:** 2  
**Priority:** P2

### User Story
- **As an** operator
- **I want** to configure follow-up issue creation when an issue completes
- **So that** downstream work (PR review, verification, monitoring) is created automatically

### Acceptance Criteria
- [x] FollowUpConfig data class with titleTemplate, descriptionTemplate, state, labels, linkType, assignee, agent fields
- [x] followUp field added to StageAgentConfig (nullable, default null)
- [x] parseFollowUp() in ServiceConfig reads from YAML
- [x] Validation: titleTemplate and state are required
- [x] Unit tests: parsing, required fields, optional fields, invalid config

### Technical Notes
- FollowUpConfig in core config package
- Parsed from stage config YAML
- Null by default (backward compatible, no behavioral change)

### Implementation
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Tests: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

---

## Story 12.2: Linear Client Extensions (Create Issue & Link)

**ID:** 12.2  
**Title:** Linear Client — Create Issue & Link  
**Points:** 3  
**Priority:** P2

### User Story
- **As a** developer
- **I want** the Linear client to support creating issues and linking them
- **So that** workflow chaining can create follow-up issues

### Acceptance Criteria
- [x] linear.createIssue(projectSlug, title, state, description, labels) returns Issue?
- [x] GraphQL mutation for issue creation
- [x] linear.createLink(sourceId, targetId, type) returns Boolean
- [x] Handle API errors gracefully (warn log, return null/false)
- [x] Unit tests with mock API responses

### Technical Notes
- Add GraphQL mutation strings for issueCreate and linkIssue
- Create mutation: `issueCreate(input: {teamId, title, description, stateId, labelIds})`
- Link mutation: `issueRelationCreate(issueId, relatedIssueId, type)`
- Resolve stateName → stateId using existing resolveStateId()

### Implementation
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt`
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearGraphQLClient.kt`
- Tests: `koncerto-linear/src/test/kotlin/com/anomaly/koncerto/linear/LinearClientTest.kt`

---

## Story 12.3: Follow-Up Template Rendering

**ID:** 12.3  
**Title:** Follow-Up Template Rendering  
**Points:** 2  
**Priority:** P2

### User Story
- **As a** developer
- **I want** follow-up issue titles and descriptions to be rendered from templates with issue data
- **So that** follow-ups are contextual

### Acceptance Criteria
- [x] renderFollowUpTemplate(template, issue) function
- [x] Supports variables: {{ issue.id }}, {{ issue.identifier }}, {{ issue.title }}, {{ issue.url }}, {{ issue.state }}, {{ issue.labels }}, {{ now }}
- [x] Unknown variable → leave as-is (not crash)
- [x] Multiple occurrences of same variable → all replaced
- [x] Unit tests for all template variables

### Technical Notes
- Simple string replacement (no Liquid dependency needed)
- {{ now }} uses current ISO timestamp
- {{ issue.labels }} renders as comma-separated string
- Keep it simple — no Liquid parser required for this use case

### Implementation
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRenderer.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/FollowUpRendererTest.kt`

---

## Story 12.4: Chain Execution in transitionOnComplete()

**ID:** 12.4  
**Title:** Chain Execution  
**Points:** 3  
**Priority:** P2

### User Story
- **As a** developer
- **I want** the orchestrator to create follow-up issues when an issue transitions to its onCompleteState
- **So that** the workflow chain is automated end-to-end

### Acceptance Criteria
- [x] transitionOnComplete() checks stageConfig.followUp != null
- [x] Renders titleTemplate and descriptionTemplate
- [x] Calls linear.createIssue() with rendered values
- [x] Calls linear.createLink() if linkType is set
- [x] Logs chain creation: "follow_up_created" with source identifier, follow-up identifier
- [x] Handles API failure gracefully (log warn, continue transition)
- [x] Follow-up issue's agent is resolved using routing rules (if followUp.agent set)
- [x] Unit tests for happy path, partial failure, invalid template

### Technical Notes
- Runs after successful state transition
- Does NOT block the main transition on follow-up failure
- Follow-up created with initial state from config
- If agent specified in followUp config, use that agent

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`
