# Epic 12: Workflow Chaining

**Story Points:** 10  
**Priority:** P2  
**Status:** Planned

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
- [ ] FollowUpConfig data class with titleTemplate, descriptionTemplate, state, labels, linkType, assignee, agent fields
- [ ] followUp field added to StageAgentConfig (nullable, default null)
- [ ] parseFollowUp() in ServiceConfig reads from YAML
- [ ] Validation: titleTemplate and state are required
- [ ] Unit tests: parsing, required fields, optional fields, invalid config

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
- [ ] linear.createIssue(projectSlug, title, state, description, labels) returns Issue?
- [ ] GraphQL mutation for issue creation
- [ ] linear.createLink(sourceId, targetId, type) returns Boolean
- [ ] Handle API errors gracefully (warn log, return null/false)
- [ ] Unit tests with mock API responses

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
- [ ] renderFollowUpTemplate(template, issue) function
- [ ] Supports variables: {{ issue.id }}, {{ issue.identifier }}, {{ issue.title }}, {{ issue.url }}, {{ issue.state }}, {{ issue.labels }}, {{ now }}
- [ ] Unknown variable → leave as-is (not crash)
- [ ] Multiple occurrences of same variable → all replaced
- [ ] Unit tests for all template variables

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
- [ ] transitionOnComplete() checks stageConfig.followUp != null
- [ ] Renders titleTemplate and descriptionTemplate
- [ ] Calls linear.createIssue() with rendered values
- [ ] Calls linear.createLink() if linkType is set
- [ ] Logs chain creation: "follow_up_created" with source identifier, follow-up identifier
- [ ] Handles API failure gracefully (log warn, continue transition)
- [ ] Follow-up issue's agent is resolved using routing rules (if followUp.agent set)
- [ ] Unit tests for happy path, partial failure, invalid template

### Technical Notes
- Runs after successful state transition
- Does NOT block the main transition on follow-up failure
- Follow-up created with initial state from config
- If agent specified in followUp config, use that agent

### Implementation
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Tests: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`
