# Issue Lifecycle State Machine

## Problem

The orchestrator manages issue state across two layers — in-memory `RuntimeState` and external Linear tracker — but there is no formal state machine enforcing valid transitions. This creates two concrete failure modes:

1. **Zombie agents**: An agent claims an issue and starts running, but the underlying process dies silently. The issue stays in `RuntimeState.running` forever, blocking re-dispatch. Linear still shows `Todo`, so no external observer can detect the stall.

2. **Re-implementation of existing work**: When an issue already has an associated branch/PR (work was started manually or by a previous agent run), dispatching it to the implementation stage redundantly redoes the work instead of just addressing existing PR feedback.

## State Machine

### States

| State | Meaning |
|-------|---------|
| `TODO` | Available to work on. Stored in Linear. |
| `IN_PROGRESS` | Agent actively implementing. Linear state is "In Progress". |
| `IN_REVIEW` | Implementation complete, agent or human reviewing. Linear state is "In Review". |
| `DONE` | Terminal. Linear state is "Done". |

### Transitions

```
                    ┌─ remote branch missing ──→ IN_PROGRESS ──agent succeeds──→ ┐
TODO ──dispatch()───┤                                                           ├──→ IN_REVIEW ──review passes──→ DONE
                    └─ remote branch exists ──→ ┘                                │
                                                                                  │ review fails
                                                                                  ↓
                                                                                 TODO
```

| From | To | Trigger | Guard |
|------|----|---------|-------|
| `TODO` | `IN_PROGRESS` | Agent dispatched | `tryClaim()` succeeds, slot available, no remote branch |
| `TODO` | `IN_REVIEW` | Agent dispatched | `tryClaim()` succeeds, slot available, remote branch exists |
| `IN_PROGRESS` | `IN_REVIEW` | Agent completes | `transitionOnComplete()` called with agent result |
| `IN_PROGRESS` | `TODO` | Stall detected | Entry in `running` exceeds `stallTimeoutMs` without heartbeat |
| `IN_REVIEW` | `DONE` | Review passes | Review decision is `Pass` or max attempts exceeded |
| `IN_REVIEW` | `TODO` | Review fails | Review decision is `RetryWithCoding` or `Blocked` |

### Zombie Detection

Every tick cycle, the reconcile loop checks each `IN_PROGRESS` entry. The stale threshold is `heartbeatTimeoutMs` (default 90s, from `AgentProjectConfig`):

```
if (now - entry.lastHeartbeatAt > heartbeatTimeoutMs) {
    linear.updateIssueState(issue.id, todoStateId)     // Reset Linear to Todo
    state.running.remove(issue.id)                      // Clean up RuntimeState
    state.releaseClaim(issue.id)
    logger.warn("zombie_detected", issue.id)
}
```

The `lastHeartbeatAt` field replaces the currently-unused `lastCodexTimestamp` on `RunningEntry`. It is updated by a periodic heartbeat from the agent (existing `heartbeatIntervalMs`/`heartbeatTimeoutMs` config is already defined but not wired to RuntimeState).

### Remote Branch Detection

During `fetchAndDispatch()`, for each candidate issue in `TODO`:

1. Compute expected branch: `branchPrefix + issue.identifier` (e.g., `feature/FLE-46`)
2. `git ls-remote --heads origin <branchName>` — check if remote branch exists
3. Branch exists → dispatch to `IN_REVIEW` stage (agent addresses PR feedback)
4. Branch missing → dispatch to `IN_PROGRESS` stage (agent implements)

The check is performed **after** slot-availability and dependency filtering so only viable candidates incur the git call.

## Implementation Plan

### `koncerto-core/.../lifecycle/IssueLifecycle.kt` (new)

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

### `koncerto-orchestrator/.../RuntimeState.kt` (modify)

- Replace `lastCodexTimestamp: Instant?` on `RunningEntry` with `lastHeartbeatAt: Instant?`
- The stale threshold uses existing `heartbeatTimeoutMs` from `AgentProjectConfig` (default 90s)

### `koncerto-orchestrator/.../DispatchService.kt` (modify)

- `prepareDispatch()`: after `tryClaim()`, call `linear.updateIssueState(id, inProgressStateId)` to transition Linear from `Todo` to `In Progress`
- `fetchAndDispatch()`: add remote-branch check for each candidate. If a remote branch exists, override the stage lookup to use the `"in review"` stage config with `onCompleteState = "In Review"`. The agent runs the review prompt (address PR feedback), and on completion transitions Linear from `Todo` → `In Review`. The normal In Review → Done pipeline handles it next tick.
- The `"in review"` stage already exists in `WORKFLOW.md` with prompt `prompts/review.md` and agent `claude`, which is suitable for addressing PR feedback.

### `koncerto-orchestrator/.../Orchestrator.kt` (modify)

- `reconcile()`: add stale detection loop for `IN_PROGRESS` entries. If `now - lastHeartbeatAt > heartbeatTimeoutMs`, auto-reset to `Todo` in Linear and clean up `RuntimeState`

### `koncerto-workspace/.../GitWorkflow.kt` (modify)

- Add `fun remoteBranchExists(branchName: String): Boolean` using `git ls-remote --heads origin <branchName>`

### `WORKFLOW.md` (modify)

- Add `"In Progress"` to `active_states` in tracker config
- Add `"In Progress"` stage to `stages` (optional — same prompt/agent as `Todo` stage, or can share config)

## Config Changes

```yaml
tracker:
  active_states:
    - Todo
    - "In Progress"
    - "In Review"
  terminal_states:
    - Done
  blocked_state: "Blocked"
```

No new config keys needed. The existing `heartbeatTimeoutMs` (default 90s) and `stallTimeoutMs` (default 300s) in `AgentProjectConfig` drive the zombie detection threshold.

## Testing

| Test | What it covers |
|------|---------------|
| State machine validates allowed transitions | `IssueLifecycle.validate()` rejects `Todo → Done`, etc. |
| Zombie detection reconcile | Create stale `RunningEntry` with old heartbeat, verify `Orchestrator.reconcile()` resets to Todo |
| Remote branch dispatch branching | Mock `remoteBranchExists` returning true/false, verify dispatch targets InReview vs InProgress |
| Heartbeat update on RunningEntry | Simulate agent heartbeat, verify `lastHeartbeatAt` updates |
