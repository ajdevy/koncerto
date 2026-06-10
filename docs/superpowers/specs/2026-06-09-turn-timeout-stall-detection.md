# Turn Timeout and Stall Detection

## Problem

The agent runtime config defines three timeout fields (`turn_timeout_ms`, `stall_timeout_ms`, `read_timeout_ms`) but none are enforced in production code. If the agent subprocess hangs, `AgentRunner.run()` returns success immediately without waiting for the turn to complete, and the issue is moved forward as if work was done. There is no mechanism to detect a stalled agent, kill it, commit partial work, and retry.

## Design

### Where: `AgentRunner.run()`

All timeout enforcement lives inside `DefaultAgentRunner.run()`. This is the natural hook point because:
- It controls the agent lifecycle (subprocess start, JSON-RPC messages, stop)
- It has access to `WorkspaceManager` and `GitWorkflow` for committing partial work
- `DispatchService` (which calls `run()`) holds the per-project `AgentProjectConfig` with timeout values

### Timeout Value Plumbing

`DispatchService.dispatch()` passes the per-project timeout values from `projectConfig.agent`:
- `turnTimeoutMs` and `stallTimeoutMs` are added as parameters to the `AgentRunner.run()` interface
- `DispatchService` reads them from its `projectConfig` and passes them to `run()`

The `AgentRuntimeFactory` also gets a `stallTimeoutMs` parameter passed through to `StdioAgentRuntime` so the low-level output reader can signal stall detection.

### Turn Timeout

The three JSON-RPC messages (initialize, thread/start, turn/start) and the output collector are wrapped in a `withTimeout(turnTimeoutMs)` coroutine block. If the turn exceeds `turnTimeoutMs`:

1. `runtime.stop()` is called (kills subprocess)
2. Output collector job is cancelled
3. Git diff is checked — if non-empty, commit and push partial work to the feature branch
4. `run()` returns `Result.Failure` with a timeout error message
5. `DispatchService.scheduleRetry()` handles the failure via existing exponential backoff

### Stall Detection

A parallel watchdog coroutine monitors `runtime.output` for lines. A `MutableStateFlow<Long>` tracks `lastOutputMs`. The watchdog polls every second:

- If current time - `lastOutputMs` > `stallTimeoutMs` → trigger timeout (same behavior as turn timeout)
- The output flow updates `lastOutputMs` on every line emitted

The watchdog is launched inside the `withTimeout` scope so if either condition fires, the entire block is cancelled.

### Commit-on-Partial

On timeout/stall, before returning:
1. `gitWorkflow.commitAndPush()` is called with the workspace path and issue identifier
2. No PR is created — partial work is just saved to the branch
3. The Linear issue comment includes "Agent timed out after N minutes; partial work saved to branch feature/ISSUE-123"

### Retry Behavior

Timeout/stall failures go through the existing `scheduleRetry()` path:
- Exponential backoff (1st: 10s, 2nd: 20s, 4th: 40s, ...) capped at `maxRetryBackoffMs`
- Counts toward `maxTurns` (default 20)
- After `maxTurns` exhausted, issue is not retried further

### Configuration

Existing config fields (already parsed, already in `AgentProjectConfig`):
- `turn_timeout_ms` — max wall-clock time per turn (default 3600000 = 1 hour)
- `stall_timeout_ms` — max idle time without output (default 300000 = 5 minutes)

No new config fields needed.

### Testing

**Unit tests for `DefaultAgentRunner`:**
- `run times out after turnTimeoutMs` — mock runtime that never completes; verify timeout failure
- `run detects stalling after stallTimeoutMs` — mock runtime that emits one line then stops; verify timeout
- `run does not timeout when turn completes before deadline` — normal completion
- `run commits partial work on timeout` — verify gitWorkflow.commitAndPush is called

**Edge cases:**
- `stallTimeoutMs` > `turnTimeoutMs` — turn timeout fires first (correct)
- `stallTimeoutMs` = 0 — immediate stall detection after first output
- Subprocess already dead before timeout — verify no crash

### Implementation Plan

1. Add `turnTimeoutMs` and `stallTimeoutMs` parameters to `AgentRunner.run()` interface
2. Update `DefaultAgentRunner.run()`: add output monitoring, wrap agent interaction in `withTimeout(turnTimeoutMs)`, add stall watchdog coroutine
3. On timeout exception: kill agent, commit partial work if non-empty, return failure with TIMED_OUT outcome
4. Update `DispatchService.dispatch()` to pass timeout values from `projectConfig.agent` to `run()`
5. Update `FakeAgentRunner` and `FailingAgentRunner` in tests for new interface
6. Write unit tests for `DefaultAgentRunner` covering timeout, stall, normal completion, and partial work commit
7. Verify all existing tests still pass
