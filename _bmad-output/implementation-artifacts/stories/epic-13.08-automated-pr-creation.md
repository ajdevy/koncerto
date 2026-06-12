# Epic 13.08: Automated PR Creation

**Story Points:** 5  
**Priority:** P2  
**Status:** Complete  

---

## Story 13.08.1: GitHubPullRequestService

**ID:** 13.08.1  
**Title:** GitHubPullRequestService  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** GitHub user
- **I want** a `GitHubPullRequestService` that creates PRs via `gh pr create`
- **So that** downstream work is initiated automatically

### Acceptance Criteria
- [x] `GitHubPullRequestService` using `gh pr create` CLI command
- [x] Supports configurable base branch, title, and body
- [x] Handles GitHub CLI authentication errors gracefully
- [x] Returns PR URL on success
- [x] Unit tests with mocked CLI execution

### Technical Notes
- Shell process execution for `gh` CLI
- Structured output parsing from `gh pr create --json`
- Fallback to GitHub API if CLI unavailable
- Error classification for common failure modes

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/GitHubPullRequestService.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/GitHubPullRequestServiceTest.kt`

---

## Story 13.08.2: FollowUpConfig PR Rules

**ID:** 13.08.2  
**Title:** FollowUpConfig PR Rules  
**Points:** 2  
**Priority:** P2  

### User Story
- **As a** GitHub user
- **I want** `FollowUpConfig` with PR creation rules
- **So that** PR creation behavior is configurable per workflow

### Acceptance Criteria
- [x] `FollowUpConfig` includes PR-specific configuration section
- [x] Configurable PR title template with variable substitution
- [x] Configurable labels, assignees, and reviewers
- [x] Draft PR option for manual review before publication
- [x] DispatchService integration for PR creation on turn completion

### Technical Notes
- PR config section in `followUp` block of workflow YAML
- Template variables: `{issueId}`, `{issueTitle}`, `{branchName}`
- Draft PRs created with `--draft` flag for GitHub CLI
- Conditional creation based on branch matching rules

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/FollowUpConfig.kt`
- References: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/DispatchService.kt`

---

## Story 13.08.3: Template-Based PR Descriptions

**ID:** 13.08.3  
**Title:** Template-Based PR Descriptions  
**Points:** 1  
**Priority:** P2  

### User Story
- **As a** GitHub user
- **I want** template-based PR description generation
- **So that** PRs have consistent, informative descriptions

### Acceptance Criteria
- [x] Template engine for PR body generation
- [x] Default template includes summary, changes, and testing notes
- [x] Custom template support via configuration
- [x] Issue references auto-included from commit history
- [x] Error handling for GitHub API failures with retry

### Technical Notes
- Simple string-template approach without external dependency
- Templates loaded from classpath or config directory
- Placeholders for dynamic content substitution
- Fallback to basic description if template rendering fails

### Implementation
- File: `koncerto-workflow/src/main/kotlin/com/anomaly/koncerto/workflow/GitHubPullRequestService.kt`
- Tests: `koncerto-workflow/src/test/kotlin/com/anomaly/koncerto/workflow/GitHubPullRequestServiceTest.kt`
