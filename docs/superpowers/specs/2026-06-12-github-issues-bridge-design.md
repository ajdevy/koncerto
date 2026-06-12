# GitHub Issues Tracker Bridge — Design Spec

**Date:** 2026-06-12
**Status:** Design

## Overview

Extensible tracker abstraction that unifies Linear and GitHub Issues under a common `TrackerClient` interface. Enables Koncerto to operate against GitHub Issues as an issue tracker while maintaining full compatibility with the existing Linear-based workflow.

## Motivation

Koncerto was designed for Linear, but many teams use GitHub Issues. Building a provider-agnostic tracker abstraction lets Koncerto work with any issue tracker, reduces vendor lock-in, and enables teams to adopt Koncerto without migrating their issue management system.

## Technical Design

### TrackerClient Interface

```kotlin
interface TrackerClient {
    suspend fun fetchCandidateIssues(slug: String, states: List<String>): List<Issue>
    suspend fun fetchIssueStatesByIds(ids: List<String>): Map<String, String>
    suspend fun fetchIssuesByStates(slug: String, states: List<String>): List<Issue>
    suspend fun updateIssueState(issueId: String, state: String): EmptyResult<DataError>
}
```

### GitHubIssuesClient Implementation

- Uses GitHub REST API (Octokit/Ktor client)
- Maps GitHub Issues labels to issue states
- Issues: `GET /repos/{owner}/{repo}/issues` with label/state filters
- State update: `PATCH /repos/{owner}/{repo}/issues/{number}` with `state` field
- Authentication: GitHub PAT via `X-GitHub-Api-Key` header

### Multi-Tracker Configuration

```yaml
projects:
  my-project:
    tracker:
      kind: github
      repo: my-org/my-repo
      api-key: ${GITHUB_TOKEN}
```

Projects independently specify their tracker kind. A single Koncerto instance can watch Linear projects and GitHub projects simultaneously.

### Fallback

When the primary tracker is unreachable, Koncerto falls back to a secondary tracker (default: Linear). This is configured at the project level.

## Testing Strategy

- `GitHubIssuesClientTest` with WireMock — mock GitHub API responses for fetch/update
- `TrackerClientFactoryTest` — verify correct client instantiation by `kind`
- `DispatchServiceMultiTrackerTest` — project with Linear tracker and GitHub tracker both active
- Fallback integration test: primary tracker returns 503 → fallback tracker used

## Open Questions

- Should we support GitLab Issues and Jira in the same abstraction, or keep the scope to Linear + GitHub for v1?
- How do we map GitHub Issues label-based states to Koncerto's expected state transitions?
