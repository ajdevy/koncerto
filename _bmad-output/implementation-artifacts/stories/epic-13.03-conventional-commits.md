# Epic 13.03: Conventional Commits

**Story Points:** 2  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.03.1: GitWorkflow CommitPrefix Method

**ID:** 13.03.1  
**Title:** GitWorkflow CommitPrefix Method  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** developer
- **I want** a `GitWorkflow.commitPrefix()` method for conventional commit messages
- **So that** commits are automatically formatted with proper prefixes

### Acceptance Criteria
- [x] `commitPrefix()` method on the `GitWorkflow` API
- [x] Mapping: `fix/bug` yields `fix:`, `docs/documentation` yields `docs:`
- [x] Support for all conventional commit types (feat, fix, docs, style, refactor, test, chore)
- [x] Automated commit prefix application in workflows
- [x] Backward compatibility with existing git history

### Technical Notes
- Extends existing GitWorkflow class without breaking API
- Pattern matching on issue type label to determine prefix
- Defaults to `chore:` for unrecognized types
- Integration with workflow step execution

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/GitWorkflow.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/GitWorkflowTest.kt`

---

## Story 13.03.2: Semantic Commit Parsing

**ID:** 13.03.2  
**Title:** Semantic Commit Parsing  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** developer
- **I want** semantic commit parsing for downstream tools
- **So that** changelogs and release notes can be auto-generated

### Acceptance Criteria
- [x] Parse commit messages into type, scope, description components
- [x] Support breaking change detection from `!` and `BREAKING CHANGE:` footer
- [x] Extract issue references from commit footers
- [x] Backward compatibility with existing commit history format
- [x] Unit tests for all parsing scenarios

### Technical Notes
- Regular expression based parser following Conventional Commits spec 1.0.0
- Extensible to support custom footers and scopes
- Integrated with existing git commit fetching
- No external dependencies beyond kotlin stdlib

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/CommitParser.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/CommitParserTest.kt`
