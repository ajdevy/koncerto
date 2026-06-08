# Koncerto E2E Test: OpenCode Agent via Full Orchestration Chain

## Summary

Add a new `koncerto-e2e` Gradle module with an end-to-end test that exercises the
full koncerto orchestration chain — from issue polling (via a fake Linear client)
through dispatch to a real opencode subprocess, culminating in the agent completing
a small coding task. This validates the integration between `Orchestrator`,
`DispatchService`, `DefaultAgentRunner`, and `OpencodeRuntime` without mocking
the agent process.

## Motivation

The existing 82 tests cover individual components in isolation (unit tests for
`DispatchService`, `RetryExecutor`, `Orchestrator`, etc.) but nothing connects
them end-to-end with a real agent subprocess. The gaps this fills:

1. **Stdio JSON-RPC handshake** — `initialize` / `thread/start` / `turn/start`
   sequence against a real opencode process.
2. **Workspace lifecycle** — `WorkspaceManager.ensureWorkspace`, shell hooks, and
   agent output landing in the workspace directory.
3. **Orchestration loop integration** — `tick()` → `reconcile()` → `fetchAndDispatch()`
   → `run()` → event collection → completion detection.
4. **OpencodeRuntime real behavior** — process spawning, event stream parsing,
   graceful shutdown.

## Scope

**In scope:**
- Single `koncerto-e2e` module with one `@Tag("e2e")` test class.
- Fake `LinearClient` returning one pre-configured issue.
- Real `Orchestrator`, `DispatchService`, `DefaultAgentRunner`, `OpencodeRuntime`,
  `WorkspaceManager`, `RuntimeState`, `RetryExecutor`, `StructuredLogger`.
- Coding task: create `hello_world.py` that prints `"Hello from Koncerto E2E"`.
- Verification: file existence + content assertion.
- Gradle task `e2eTest` (tag-included) separate from default `test` (tag-excluded).

**Out of scope:**
- Real Linear API integration (remains unit-tested separately).
- Spring Boot context wiring (tested by `AppTest` / `DashboardControllerTest`).
- Coverage of error paths (retries, agent crashes, Linear failures — covered by
  unit tests).
- Multiple concurrent issues or multi-turn conversations.

## Architecture

### Module layout

```
koncerto-e2e/
├── build.gradle.kts
└── src/test/kotlin/
    └── com/anomaly/koncerto/e2e/
        └── OpenCodeE2eTest.kt
```

`build.gradle.kts` depends on:
- `project(":koncerto-orchestrator")`
- `project(":koncerto-agent")`
- `project(":koncerto-core")`
- `project(":koncerto-linear")` — for `LinearClient` interface and fake
- `project(":koncerto-workspace")`

### Data flow

```
┌─────────────────────────────────────────────────────────────┐
│                      OpenCodeE2eTest                          │
│  ┌─────────────────────────────────────────────────────────┐ │
│  │  @Test                                                  │ │
│  │  1. Create temp workspace dir                           │ │
│  │  2. Build minimal ServiceConfig (agent.kind=opencode)   │ │
│  │  3. Instantiate FakeLinearClient with one issue          │ │
│  │  4. Wire real components (no Spring)                     │ │
│  │  5. Launch orchestration in coroutine scope              │ │
│  │  6. Poll RuntimeState.completed with 180s timeout        │ │
│  │  7. Assert hello_world.py exists + content               │ │
│  │  8. Cancel scope, stop runtime, clean workspace           │ │
│  └─────────────────────────────────────────────────────────┘ │
│                           │                                    │
│                           ▼                                    │
│              ┌──────────────────────────┐                      │
│              │     Orchestrator         │                      │
│              │  start(CoroutineScope)   │                      │
│              │  tick() every 1s         │                      │
│              └────────┬─────────────────┘                      │
│                       │                                         │
│              ┌────────▼─────────────────┐                      │
│              │   DispatchService        │                      │
│              │   fetchAndDispatch()     │                      │
│              └────────┬─────────────────┘                      │
│                       │                                         │
│              ┌────────▼─────────────────┐                      │
│              │  DefaultAgentRunner      │                      │
│              │  → ensureWorkspace       │                      │
│              │  → render prompt         │                      │
│              │  → OpencodeRuntime.start │                      │
│              └────────┬─────────────────┘                      │
│                       │                                         │
│              ┌────────▼─────────────────┐                      │
│              │  OpencodeRuntime          │  ← REAL subprocess   │
│              │  stdio JSON-RPC:          │                      │
│              │  initialize → thread/start│                      │
│              │  → turn/start             │                      │
│              │  → wait for completion    │                      │
│              └────────┬─────────────────┘                      │
│                       │                                         │
│              ┌────────▼─────────────────┐                      │
│              │  Workspace               │                      │
│              │  /hello_world.py          │                      │
│              └──────────────────────────┘                      │
└─────────────────────────────────────────────────────────────┘
```

### FakeLinearClient

A minimal implementation of the `LinearClient` interface that returns a single
hard-coded `Issue` object:

```kotlin
class FakeLinearClient(private val issue: Issue) : LinearClient {
    override suspend fun fetchCandidateIssues(slug: String, states: List<String>): List<Issue> = listOf(issue)
    override suspend fun fetchIssueStatesByIds(ids: List<String>): Map<String, String> =
        mapOf(issue.id to issue.state)
    override suspend fun fetchIssuesByStates(slug: String, states: List<String>): List<Issue> = listOf(issue)
}
```

### Test issue

```kotlin
fun sampleIssue() = Issue(
    id = "e2e-1",
    identifier = "E2E-1",
    title = "Create hello script",
    description = "Create a Python script named hello_world.py in the workspace root. " +
        "The script should print 'Hello from Koncerto E2E' when executed.",
    state = "In Progress",
    priority = 5,
    labels = listOf("e2e-test"),
    blockedBy = emptyList(),
    branchName = null,
    url = null,
    createdAt = null,
    updatedAt = null
)
```

### Configuration

The test builds a `ServiceConfig` with only the essential overrides:

| Key | Value | Purpose |
|-----|-------|---------|
| `agent.kind` | `"opencode"` | Select OpencodeRuntime |
| `linear.team.slug` | `"e2e-test"` | Matches FakeLinearClient |
| `linear.poll-interval-sec` | `1` | Fast polling for test |
| `workspace.base-dir` | temp dir path | Isolated workspace |
| `agent.prompt` | `"default"` | Uses default workflow |

All other fields use test defaults (matching `config()` helper in `DispatchServiceTest`): `trackerEndpoint = "x"`, `trackerApiKey = "k"`, `maxTurns = 10`, etc. — only the keys above differ from those defaults.

### Timeouts

- **Orchestration timeout**: 180 seconds (deepseek free model can be slow).
- **Polling interval**: 500ms checks on `state.completed`.
- **Process cleanup**: `job.cancel()` + `runtime.stop()` in `@AfterEach` or
  finally block.

### Gradle task configuration

In `koncerto-e2e/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(project(":koncerto-orchestrator"))
    testImplementation(project(":koncerto-agent"))
    testImplementation(project(":koncerto-core"))
    testImplementation(project(":koncerto-linear"))
    testImplementation(project(":koncerto-workspace"))
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
    group = "verification"
    description = "Runs end-to-end tests (requires opencode CLI)"
}
```

Add `include("koncerto-e2e")` to `settings.gradle.kts`.

### Verification

```kotlin
val file = workspaceDir.resolve("hello_world.py")
assertThat(file.exists()).isTrue()
assertThat(file.readText()).contains("Hello from Koncerto E2E")
```

### Cleanup

`@AfterEach` or finally block:
1. Cancel the orchestration coroutine job.
2. Call `orchestrator.stop()` to shut down the agent process.
3. Delete the temporary workspace directory.

### Running

```bash
# Default build (skips e2e):
./gradlew build

# E2E only (requires `opencode` on PATH):
./gradlew :koncerto-e2e:e2eTest
```

## Considerations

### opencode CLI not found
If `opencode` is not on PATH, the `OpencodeRuntime` will fail during process
start. The test should detect this early with `assumeTrue` or fail with a clear
message: `"opencode CLI not found on PATH; install it from https://opencode.ai"`.

### Network dependency
The free deepseek model requires internet access. If the API is unreachable,
the agent turn may fail. The test will time out and the assertion should include
diagnostics from the agent event stream.

### Slowness
Deepseek free model can take 30-90s. The 180s timeout accounts for this.
The test is excluded from `./gradlew build` so it won't slow CI.

### Module count
Adding a module is intentional — the `koncerto-e2e` boundary keeps E2E concerns
isolated and prevents accidental inclusion in default builds. The module has no
production code, only test sources.

## Future extensions

- Parameterize the agent kind (opencode vs codex) via `@ValueSource`.
- Add a second scenario: agent fixing a small bug in existing code.
- Add a failure scenario: agent times out → retry → eventually succeeds.
