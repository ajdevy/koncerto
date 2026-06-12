# Automated PR Creation — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Automatic pull request creation on GitHub when an agent completes a workflow stage. Integrates with the GitHub CLI (`gh pr create`) to produce PRs with structured titles, labels, reviewers, and descriptions derived from the completed work.

## Motivation

After an implementation agent finishes coding, a human or reviewer agent needs to examine the changes. Automated PR creation eliminates the manual step of creating a PR — the agent's changes are immediately packaged into a reviewable PR with context from the agent run (issue description, files changed, token usage, model used).

## Technical Design

### Service

`GitHubPullRequestService` wraps `gh pr create`:

```kotlin
class GitHubPullRequestService(
    private val baseDir: Path,
    private val config: FollowUpConfig
) {
    suspend fun create(issue: Issue, result: AgentResult): Result<PullRequest>
}
```

### PR Template

Title: `[Koncerto] {issue.identifier}: {issue.title}`
Body: Issue description + agent summary + metadata table (model, tokens, duration)
Labels: From `FollowUpConfig.labels` + `koncerto-auto`
Draft mode: Configurable — `draft: true` produces a draft PR for manual review

### Trigger Points

- On `turnCompletion` in `DispatchService` (after agent run succeeds)
- Conditional on `FollowUpConfig.createPR` being `true`
- Configurable per-stage: stage-level `create_pr: true/false` override

## Configuration

```yaml
koncerto:
  follow-up:
    create-pr: true
    draft: true
    labels: [koncerto-auto, needs-review]
    reviewers: [team-lead]
    base-branch: main
```

## Testing Strategy

- `GitHubPullRequestServiceTest` with mock process — verify `gh pr create` arguments
- `DispatchServicePRTest` — verify PR creation is triggered on agent completion when config enabled
- Integration: requires GitHub CLI with valid token

## Open Questions

- Should we support GitLab MR creation alongside GitHub PRs?
- How do we handle the case where a branch already has an open PR — update it or create a new one?
