---
baseline_commit: 8ccf6bb
status: review
---

# Story: Mandatory Comment on Blocked State Transition

**ID:** blocked-state-comment  
**Title:** Every transition to Blocked state must post a Linear comment  
**Story Points:** 2  
**Priority:** P1  
**Status:** in-progress  

---

## User Story

- **As a** developer looking at a blocked Linear issue
- **I want** to see a comment explaining why the issue was blocked
- **So that** I can act on it without having to trace logs or contact the team

## Acceptance Criteria

- [ ] AC1: When `ModelRetryHandler.handleExhaustion` transitions an issue to Blocked, it first posts a comment summarising which models were tried and how many retries were made
- [ ] AC2: When `AutoReviewOrchestrator.handleReviewExhaustion` transitions an issue to Blocked, it first posts a comment stating that the review gate failed and how many attempts were made
- [ ] AC3: When `DispatchService` transitions an issue to `onFailureState` (defaulting to Blocked) after retry exhaustion, it first posts a comment stating the issue was blocked due to retry exhaustion with the error detail
- [ ] AC4: All three comment calls are best-effort — a failure to create the comment is logged but does not prevent the state transition
- [ ] AC5: Tests verify that `createComment` is called with a non-empty message before `updateIssueState` in all three exhaustion paths

---

## Tasks / Subtasks

- [x] Task 1: Add comment before state transition in `ModelRetryHandler.handleExhaustion`
  - [x] 1a. Write failing test: `handleExhaustion posts comment before blocking`
  - [x] 1b. Add `createComment` call in `handleExhaustion` before `updateIssueState`
  - [x] 1c. Verify test passes

- [x] Task 2: Add comment before state transition in `AutoReviewOrchestrator.handleReviewExhaustion`
  - [x] 2a. Write failing test: `handleReviewExhaustion posts comment before blocking`
  - [x] 2b. Add `createComment` call in `handleReviewExhaustion` before `updateIssueState`
  - [x] 2c. Verify test passes

- [x] Task 3: Add comment before state transition in `DispatchService` retry exhaustion path
  - [x] 3a. Write failing test: `retry exhaustion posts comment before blocking state transition`
  - [x] 3b. Add `createComment` call in retry exhaustion block before `updateIssueState`
  - [x] 3c. Verify test passes

- [x] Task 4: Run full regression suite

- [x] Task 5: Update docs (WORKFLOW.md, architecture doc)

---

## Dev Notes

### Sites to change

| File | Method | Line (approx) |
|------|--------|---------------|
| `koncerto-agent/src/main/kotlin/com/flexsentlabs/koncerto/agent/ModelRetryHandler.kt` | `handleExhaustion` | 100–110 |
| `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt` | `handleReviewExhaustion` | 207–218 |
| `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DispatchService.kt` | retry exhaustion block | 170–180 |

### Pattern (already used in clarification path, DispatchService ~line 824)

```kotlin
try {
    linearClient.createComment(issueId, "<reason>")
} catch (e: Exception) {
    logger.warn("blocked_comment_failed", mapOf("issue_id" to issueId, "error" to (e.message ?: "unknown")))
}
// then:
linearClient.updateIssueState(issueId, blockedStateId)
```

### Comment messages

- Model exhaustion: `"Blocked: all free models exhausted after ${exhausted.totalRetries} retries (tried: ${exhausted.modelsTried.joinToString()})"`
- Review exhaustion: `"Blocked: review gate failed after $totalAttempts attempts"`
- Retry exhaustion (DispatchService): `"Blocked: retry limit reached. Last error: $error"`

### Test infrastructure

- `ModelRetryHandlerTest` uses `mockk<TrackerClient>()` — add `coEvery { linearClient.createComment(any(), any()) } just runs` and `coVerify { linearClient.createComment("issue-1", match { it.contains("exhausted") }) }`
- `AutoReviewOrchestratorTest` has `fakeTracker()` stub — extend it or add a tracking variant
- `DispatchServiceTest` has `TrackingLinearClient` which already records `commentedIssueId`/`commentedBody`

---

## Dev Agent Record

### Implementation Plan

1. TDD: write failing test → add comment call → green → repeat for each site
2. Comment call is best-effort (wrapped in try/catch, non-blocking)
3. Comment must come BEFORE state transition (mirrors existing clarification pattern)

### Debug Log

_empty_

### Completion Notes

Three sites fixed with identical pattern: best-effort `createComment` wrapped in try/catch before the existing `resolveStateId` + `updateIssueState` block. The clarification path (DispatchService.handleClarification) was already compliant and untouched.

6 new tests added (2 per site): one verifies comment is called before state transition via call-order tracking; one verifies the state transition still fires even when comment throws. Full regression suite (excluding e2e) passes.

---

## File List

- `koncerto-agent/src/main/kotlin/com/flexsentlabs/koncerto/agent/ModelRetryHandler.kt` (modified)
- `koncerto-agent/src/test/kotlin/com/flexsentlabs/koncerto/agent/ModelRetryHandlerTest.kt` (modified)
- `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestrator.kt` (modified)
- `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/AutoReviewOrchestratorTest.kt` (modified)
- `koncerto-orchestrator/src/main/kotlin/com/flexsentlabs/koncerto/orchestrator/DispatchService.kt` (modified)
- `koncerto-orchestrator/src/test/kotlin/com/flexsentlabs/koncerto/orchestrator/DispatchServiceTest.kt` (modified)
- `_bmad/bmm/architecture/issue-lifecycle-state-machine.md` (modified — Blocked state contract added)
- `WORKFLOW.md` (modified — inline invariant note on `blocked_state`)
- `_bmad-output/implementation-artifacts/stories/story-blocked-state-comment.md` (new)

---

## Change Log

| Date | Change |
|------|--------|
| 2026-06-27 | Story created, status set to in-progress |
| 2026-06-28 | All tasks complete — 3 sites fixed, 6 tests added (2 per site: ordering + resilience), full regression green, docs updated. Status → review |
