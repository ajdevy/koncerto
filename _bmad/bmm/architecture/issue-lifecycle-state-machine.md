# Architecture Document: Issue Lifecycle State Machine

> **Status: Implemented** — IssueLifecycle.kt in koncerto-core, AutoReviewOrchestrator.kt in koncerto-orchestrator, remoteBranchExists() in koncerto-workspace.

## 1. System Overview

The orchestrator manages issue state across two layers — in-memory `RuntimeState` and external Linear tracker — but there is no formal state machine enforcing valid transitions. This creates two concrete failure modes:

1. **Zombie agents**: An agent claims an issue and starts running, but the underlying process dies silently. The issue stays in `RuntimeState.running` forever, blocking re-dispatch. Linear still shows `Todo`, so no external observer can detect the stall.

2. **Re-implementation of existing work**: When an issue already has an associated branch/PR (work was started manually or by a previous agent run), dispatching it to the implementation stage redundantly redoes the work instead of addressing existing PR feedback.

## 2. Technology Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Language | Kotlin | 1.9+ |
| Framework | Spring Boot | 3.x |
| Build | Gradle | 8.x |
| Async | Kotlin Coroutines + Flow | 1.7+ |
| Tracker | Linear (GraphQL API) | — |
| Git | JGit / shell | — |

## 3. Module Architecture

### Module Dependency Graph

```
koncerto-core  ←  koncerto-orchestrator  →  koncerto-linear
                                    ↓
                           koncerto-workspace
```

### Module Responsibilities

| Module | Responsibility | Key Changes |
|--------|---------------|-------------|
| `koncerto-core` | State machine definition (sealed interface + transition matrix) | New `IssueLifecycle.kt` |
| `koncerto-orchestrator` | RuntimeState, dispatch flow, reconcile loop | Modify `RuntimeState`, `DispatchService`, `Orchestrator` |
| `koncerto-workspace` | Git operations (remote branch check) | Add `remoteBranchExists()` to `GitWorkflow` |
| `koncerto-linear` | Linear API communication | No changes needed |

## 4. Data Flow

### State Machine

```
                    ┌─ remote branch missing ──→ IN_PROGRESS ──agent succeeds──→ ┐
TODO ──dispatch()───┤                                                           ├──→ IN_REVIEW ──review passes──→ DONE
                    └─ remote branch exists ──→ ┘                                │
                                                                                  │ review fails
                                                                                  ↓
                                                                                 TODO
```

### Transitions

| From | To | Trigger | Guard |
|------|----|---------|-------|
| `TODO` | `IN_PROGRESS` | Agent dispatched | `tryClaim()` succeeds, slot available, no remote branch |
| `TODO` | `IN_REVIEW` | Agent dispatched | `tryClaim()` succeeds, slot available, remote branch exists |
| `IN_PROGRESS` | `IN_REVIEW` | Agent completes | `transitionOnComplete()` called with agent result |
| `IN_PROGRESS` | `TODO` | Stall detected | Entry in `running` exceeds `heartbeatTimeoutMs` without heartbeat |
| `IN_REVIEW` | `READY_FOR_HUMAN_REVIEW` | Review passes | Auto-review verdict is ✅ PASS |
| `IN_REVIEW` | `TODO` | Review fails | Auto-review verdict is ❌ FAIL, re-dispatch |
| `IN_REVIEW` | `DONE` | Review passes + no human review stage | Max review attempts exceeded |
| `READY_FOR_HUMAN_REVIEW` | `DONE` | Human completes review | Manual state transition in Linear |

### Extended Flow with Auto-Review

```
                      ┌─ review_passed ──→ deploy (optional) ──→ demo recording (optional)
                      │                                              │
IN_REVIEW ──auto──────┤                                              ▼
                      │                                        postDetailedReviewAsPrComment()
                      │                                         + 🎥 demo URL
                      │                                              │
                      │                                              ▼
                      │                                        READY_FOR_HUMAN_REVIEW
                      │
                      └─ ❌ FAIL ──→ TODO (re-dispatch, up to max_review_attempts)
```

### Zombie Detection

Every tick cycle, the reconcile loop checks each `IN_PROGRESS` entry:

```
if (now - entry.lastHeartbeatAt > heartbeatTimeoutMs) {
    linear.updateIssueState(issue.id, todoStateId)
    state.running.remove(issue.id)
    state.releaseClaim(issue.id)
    logger.warn("zombie_detected", issue.id)
}
```

### Remote Branch Detection

During `fetchAndDispatch()`, for each candidate issue in `TODO`:

1. Compute expected branch: `branchPrefix + issue.identifier` (e.g., `feature/FLE-46`)
2. `git ls-remote --heads origin <branchName>` — check if remote branch exists
3. Branch exists → dispatch to `IN_REVIEW` stage with `onCompleteState = "In Review"` (agent addresses PR feedback, transitions Todo → In Review)
4. Branch missing → dispatch to `IN_PROGRESS` stage (agent implements, transitions Todo → In Progress → In Review)

The check is performed **after** slot-availability and dependency filtering.

## 5. Internal Interfaces

### IssueLifecycle (new, koncerto-core)

```kotlin
sealed interface IssueLifecycle {
    data object Todo : IssueLifecycle
    data object InProgress : IssueLifecycle
    data object InReview : IssueLifecycle
    data object Done : IssueLifecycle

    data class Transition(
        val from: IssueLifecycle,
        val to: IssueLifecycle,
        val trigger: String,
        val guard: (ctx: TransitionContext) -> Boolean = { true }
    )

    companion object {
        fun allowedTransitions(): List<Transition> = listOf(
            Transition(Todo, InProgress, "dispatch"),
            Transition(Todo, InReview, "dispatch_has_pr"),
            Transition(InProgress, InReview, "complete"),
            Transition(InProgress, Todo, "stall_timeout"),
            Transition(InReview, Done, "review_pass"),
            Transition(InReview, Todo, "review_fail"),
        )

        fun validate(from: IssueLifecycle, to: IssueLifecycle, trigger: String): Boolean {
            return allowedTransitions().any { t ->
                t.from == from && t.to == to && t.trigger == trigger
            }
        }
    }
}
```

### GitWorkflow (modified, koncerto-workspace)

```kotlin
fun remoteBranchExists(branchName: String): Boolean
```

Uses `git ls-remote --heads origin <branchName>` and returns `true` if the remote has a matching ref.

## 6. Data Models

### RunningEntry (modified, koncerto-orchestrator)

| Field | Old | New |
|-------|-----|-----|
| `lastCodexTimestamp` | `Instant?` (unused, always null) | Removed |
| `lastHeartbeatAt` | — | `Instant?` — updated by periodic agent heartbeat |

The stale threshold uses existing `heartbeatTimeoutMs` from `AgentProjectConfig` (default 90s).

## 7. Security Considerations

No new security concerns. All state transitions are server-side, authenticated through existing Linear API credentials. The `git ls-remote` check is read-only and uses existing git auth.

## 8. Performance Requirements

| Concern | Mitigation |
|---------|------------|
| `git ls-remote` per candidate | Performed after slot-availability filtering; only viable candidates incur the call |
| Heartbeat overhead | Existing 30s interval, minimal — single timestamp write per agent |
| Reconcile complexity | O(n) over running entries, reads only `lastHeartbeatAt` — negligible |

## 9. Deployment

### Config Example (`WORKFLOW.md`)

```yaml
tracker:
  active_states:
    - Todo
    - "In Progress"
    - "In Review"
  terminal_states:
    - Done
  blocked_state: "Blocked"
agent:
  stages:
    Todo:          # → codex implements, onComplete → "In Review"
      agent_kind: codex
      on_complete_state: "In Review"
    "In Progress": # → claude safety-net check
      agent_kind: claude
      on_complete_state: "In Review"
    "In Review":   # → claude auto-review, onComplete → "Ready for Human Review"
      agent_kind: claude
      on_complete_state: "Ready for Human Review"
      max_review_attempts: 3
    "Ready for Human Review":
      agent_kind: human
      on_complete_state: "Done"
```

Key config: `heartbeatTimeoutMs` (default 90s from `AgentProjectConfig`) drives zombie detection.

### Order of Deployment

1. Add `"In Progress"` to `active_states` in WORKFLOW.md
2. Create `IssueLifecycle.kt` in `koncerto-core`
3. Modify `RunningEntry` — replace `lastCodexTimestamp` with `lastHeartbeatAt`
4. Add `remoteBranchExists()` to `GitWorkflow`
5. Modify `DispatchService` — dispatch-time Linear transition + branch-aware dispatch
6. Modify `Orchestrator.reconcile()` — zombie detection sweep

## 10. Technical Decisions

| Decision | Options Considered | Choice | Rationale |
|----------|-------------------|--------|-----------|
| State machine library | Sealed-class enum, Tinder StateMachine, Event-sourced | Sealed-class + transition matrix | Zero new dependencies, follows existing patterns (circuit breakers, subtask states already use sealed classes) |
| Zombie detection threshold | `stallTimeoutMs` (300s), `heartbeatTimeoutMs` (90s) | `heartbeatTimeoutMs` | Tighter detection window, directly tied to agent health signal |
| Remote branch detection | Linear `branchName` field, `git ls-remote`, both | `git ls-remote` | Checks actual remote state, not metadata. Deterministic (branch name = prefix + identifier) |
| Has-PR dispatch target | Skip to Done, skip to In Review | Skip to In Review | User wants human review before Done even for PR-feedback work |

## 11. Testing Strategy

| Test | Coverage | Status |
|------|----------|--------|
| State machine validation | `IssueLifecycle.validate()` rejects illegal transitions (e.g., `Todo → Done`) | ✅ |
| Zombie detection reconcile | Create stale `RunningEntry` with old heartbeat, verify `reconcile()` resets to Todo | ✅ |
| Remote branch dispatch | Mock `remoteBranchExists` true/false, verify dispatch targets InReview vs InProgress | ✅ |
| Heartbeat update on RunningEntry | Simulate agent heartbeat, verify `lastHeartbeatAt` updates | ✅ |
| `remoteBranchExists` unit | Mock `git ls-remote` output, verify true/false for existing/missing branches | ✅ |
| Auto-review verdict parsing | Verify ❌ FAIL → review_failed, ✅ PASS → review_passed | ✅ Verified e2e |
| PR comment posting | Verify gh pr comment --body-file with correct repo/PR# | ✅ Verified e2e |
| Demo recording pipeline | Verify Playwright → ffmpeg → R2 upload → 200 OK | ✅ Verified e2e (FLE-51) |
| Target project deploy | Verify Docker build → container start → health check | ✅ Verified e2e (FLE-75) |
