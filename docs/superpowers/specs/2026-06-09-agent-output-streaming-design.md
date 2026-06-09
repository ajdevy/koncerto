# Agent Output Streaming — Design Spec

**Date:** 2026-06-09
**Status:** Design

## Goal

Allow users to view live raw stdout/stderr output from currently executing agents via the web dashboard using Server-Sent Events (SSE).

## Non-goals

- Historical output after agent completes (output buffer discarded on cleanup)
- WebSocket, polling, or CLI-based viewing
- Structured/parsed event streaming — raw lines only
- Persistent log storage

## Architecture

### Data flow

```
Agent subprocess (bash -lc <command>)
  │ stdout lines
  ▼
StdioAgentRuntime.readStdout coroutine
  ├─ tee raw line → MutableSharedFlow<String> (before JSON-RPC parse)
  └─ parse JSON-RPC → Channel<AgentEvent> (existing)
        │
        ▼ (per each LineRaw event)
DefaultAgentRunner.run()  ← subscribes to runtime.output
  │ appendOutput(issueId, line)
  ▼
RuntimeState.outputBuffers[issueId]: MutableSharedFlow<String>
  │                                replay=2000, extraBufferCapacity=500
  ▼ (SSE endpoint)
ApiV1Controller.streamOutput()
  │ SharedFlow.asPublisher() → Flux → SseEvent
  ▼
Dashboard JS  ← EventSource(url)
```

### 1. Agent Runtime: raw output flow

**File:** `koncerto-agent/.../StdioAgentRuntime.kt`

Both `readStdout` and `readStderr` coroutines tee their raw lines to a shared `MutableSharedFlow<String>` before existing processing (JSON-RPC parse for stdout, logging for stderr):

```kotlin
private val _output = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
override val output: SharedFlow<String> = _output.asSharedFlow()
```

In `readStdout`:
```kotlin
val line = reader.readLine() ?: break
_output.tryEmit("[stdout] $line")  // tag source for clarity
// ... existing JSON-RPC decode + dispatch
```

In `readStderr`:
```kotlin
val line = reader.readLine() ?: break
_output.tryEmit("[stderr] $line")  // tag source
// ... existing stderr logging
```

**File:** `koncerto-agent/.../AgentRuntime.kt`

Interface gets a new property:
```kotlin
val output: SharedFlow<String>
```

No changes needed to `CodexRuntime` or `OpencodeRuntime` — they inherit from `StdioAgentRuntime`.

### 2. RuntimeState: output buffer

**File:** `koncerto-orchestrator/.../RuntimeState.kt`

Add:
```kotlin
private val outputBuffers = ConcurrentHashMap<String, MutableSharedFlow<String>>()

fun appendOutput(issueId: String, line: String) {
    val flow = outputBuffers.getOrPut(issueId) {
        MutableSharedFlow(replay = 2000, extraBufferCapacity = 500)
    }
    flow.tryEmit(line)
}

fun outputFlow(issueId: String): SharedFlow<String>? = outputBuffers[issueId]

fun removeOutput(issueId: String) {
    outputBuffers.remove(issueId)
}
```

`removeOutput` is called from `reconcile()` when a running issue is removed (completion, transition, error).

### 3. DefaultAgentRunner: output callback

**File:** `koncerto-agent/.../AgentRunner.kt`

`DefaultAgentRunner` is in `koncerto-agent`, which does not depend on `koncerto-orchestrator` (where `RuntimeState` lives). To avoid a circular dependency, use a callback parameter:

```kotlin
class DefaultAgentRunner(
    ...
    private val onAgentOutput: ((issueId: String, line: String) -> Unit)? = null
) : AgentRunner {
```

Inside `run()`, after `runtime.start()` and before `runtime.stop()`:
```kotlin
val outputJob = if (onAgentOutput != null) {
    scope.launch {
        runtime.output.collect { line ->
            onAgentOutput!!(issue.id, line)
        }
    }
} else null
```

On cleanup (after `runtime.stop()`):
```kotlin
outputJob?.cancel()
```

Wiring in `Beans.kt` (in `koncerto-app`, which depends on both modules):
```kotlin
DefaultAgentRunner(
    ...existing args...,
    onAgentOutput = { issueId, line -> runtimeState.appendOutput(issueId, line) }
)
```

### 4. SSE endpoint

**File:** `koncerto-dashboard/.../ApiV1Controller.kt`

Add:
```kotlin
@GetMapping("/running/{identifier}/output/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun streamOutput(@PathVariable identifier: String): Flux<ServerSentEvent<String>> {
    val entry = state.running.values.firstOrNull { it.issue.identifier == identifier }
        ?: return Flux.empty()
    val flow = state.outputFlow(entry.issue.id) ?: return Flux.empty()
    return Flux.from(flow.asPublisher())
        .map { ServerSentEvent.builder(it)
            .id(UUID.randomUUID().toString())
            .event("output")
            .build()
        }
        .doFinally { /* client disconnected — no cleanup needed, buffer managed by RuntimeState */ }
}
```

Lookup by `identifier` (e.g., "A-1") matches the existing `GET /api/v1/{identifier}` convention. The `outputFlow` lookup by `issue.id` matches `RuntimeState.running` keying.

The `replay = 2000` on the SharedFlow means the client immediately receives the last 2000 lines on connect, then gets live lines as they arrive.

**Dependency:** Add `kotlinx-coroutines-reactive` to `koncerto-dashboard/build.gradle.kts`:
```kotlin
implementation(libs.kotlinx.coroutines.reactive)
```

### 5. Dashboard HTML

**File:** `koncerto-dashboard/.../resources/templates/dashboard.html`

Add an output viewer section for each running agent. When dashboard renders the running table, each row gets an "Output" button that toggles a `<pre>` area. On click, `EventSource` connects to the SSE endpoint and appends lines to the `<pre>`.

```javascript
function attachOutput(identifier) {
    const pre = document.getElementById('output-' + identifier);
    const evtSource = new EventSource('/api/v1/running/' + identifier + '/output/stream');
    evtSource.addEventListener('output', function(event) {
        pre.textContent += event.data + '\n';
        pre.scrollTop = pre.scrollHeight;
    });
    // Store reference for cleanup
    window.outputSources = window.outputSources || {};
    window.outputSources[identifier] = evtSource;
}
```

On agent completion (when the issue disappears from the running table), `evtSource.close()` is called.

### Error/edge cases

| Case | Behavior |
|------|----------|
| Agent finishes, client still connected | SSE stream stays open until client disconnect. `reconcile()` calls `removeOutput()` — SharedFlow is removed from the map but the SSE subscriber keeps any buffered reference until disconnect. No leak because GC collects when client disconnects. |
| No agent found for identifier | `running` lookup returns `null`, endpoint returns `Flux.empty()` (immediate SSE close). |
| Agent hasn't started producing output yet | Client connects, gets replay cache (empty for first connect), then waits for live lines. |
| Buffer overflow (fast output, slow client) | `extraBufferCapacity = 500` absorbs bursts. If overflow, `onBufferOverflow = DROP_OLDEST` is the default for `tryEmit`. Some older lines are dropped, never recent ones. |
| Concurrent writes to same agent flow | `tryEmit` is thread-safe (SharedFlow contract). |

## Files changed

| File | Change |
|------|--------|
| `koncerto-agent/.../StdioAgentRuntime.kt` | Add `_output` flow, tee raw lines in `readStdout` and `readStderr` |
| `koncerto-agent/.../AgentRuntime.kt` | Add `val output: SharedFlow<String>` to interface |
| `koncerto-agent/.../AgentRunner.kt` | `DefaultAgentRunner` accepts `onAgentOutput` callback, subscribes to runtime output |
| `koncerto-orchestrator/.../RuntimeState.kt` | Add `outputBuffers` map + `appendOutput`/`outputFlow`/`removeOutput` |
| `koncerto-app/.../Beans.kt` | Wire `onAgentOutput` callback in `DefaultAgentRunner` |
| `koncerto-dashboard/.../ApiV1Controller.kt` | Add SSE endpoint |
| `koncerto-dashboard/build.gradle.kts` | Add `kotlinx-coroutines-reactive` |
| `koncerto-dashboard/.../dashboard.html` | Output viewer per agent row |

## Testing

- **Unit:** `RuntimeState` appendOutput/outputFlow/removeOutput with 2000-line replay
- **Unit:** SSE endpoint returns `Flux.empty()` for unknown identifier
- **Integration:** (manual) Run dashboard, observe output lines appearing in near-real-time for a running agent
