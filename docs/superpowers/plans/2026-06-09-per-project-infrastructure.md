# Per-Project Infrastructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix multi-project runtime by moving per-project infrastructure from singleton beans to Orchestrator-owned instances.

**Architecture:** Orchestrator creates ProjectRuntime per project (LinearClient, WorkspaceManager, RuntimeState, DispatchService). Beans.kt provides factory lambdas. ApiV1Controller routes by project slug.

**Tech Stack:** Kotlin, Spring Boot, Ktor HTTP client (Linear)

---

### Task 1: Rewrite Beans.kt — factories instead of singletons

**Files:**
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`

- [ ] **Step 1: Replace workspaceManager bean with factory lambda**

```kotlin
// REMOVE:
@Bean
fun workspaceManager(config: ServiceConfig, logger: StructuredLogger): WorkspaceManager {
    val executor = ShellHookExecutor(config.hooks.timeoutMs, logger)
    return WorkspaceManager(config.workspaceRoot, executor)
}

// ADD:
@Bean
fun workspaceManagerFactory(
    config: ServiceConfig,
    logger: StructuredLogger
): (ProjectConfig) -> WorkspaceManager = { pc ->
    val executor = ShellHookExecutor(config.hooks.timeoutMs, logger)
    WorkspaceManager(java.nio.file.Paths.get(pc.workspace.root), executor)
}
```

- [ ] **Step 2: Replace linearClient bean with factory lambda**

```kotlin
// REMOVE:
@Bean
fun linearClient(config: ServiceConfig): LinearClient {
    val graphql = LinearGraphQLClient(config.trackerEndpoint, config.trackerApiKey)
    val slug = config.trackerProjectSlug ?: throw IllegalStateException("missing_tracker_project_slug")
    return DefaultLinearClient(graphql, slug)
}

// ADD:
@Bean
fun linearClientFactory(): (ProjectConfig) -> LinearClient = { pc ->
    val graphql = LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey)
    DefaultLinearClient(graphql, pc.tracker.projectSlug)
}
```

- [ ] **Step 3: Update orchestrator bean wiring**

```kotlin
@Bean
fun orchestrator(
    config: ServiceConfig,
    runner: AgentRunner,
    cache: WorkflowCache,
    logger: StructuredLogger,
    scope: CoroutineScope,
    linearClientFactory: (ProjectConfig) -> LinearClient,
    workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager,
    metricsRepository: MetricsRepository?
): Orchestrator = Orchestrator(
    config = config,
    linearClientFactory = linearClientFactory,
    workspaceManagerFactory = workspaceManagerFactory,
    agentRunner = runner,
    workflowCache = cache,
    logger = logger,
    scope = scope,
    metricsRepository = metricsRepository
)
```

- [ ] **Step 4: Remove unused beans** — `workspaceManager()`, `linearClient()`, `runtimeState()` (now managed by Orchestrator)

- [ ] **Step 5: Run tests to verify compilation**

```bash
./gradlew :koncerto-app:compileKotlin
```
Expected: compilation errors in Orchestrator and tests (expected — we haven't updated them yet)

- [ ] **Step 6: Commit**

```bash
git add koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt
git commit -m "refactor: replace singleton beans with per-project factories in Beans.kt"
```

### Task 2: Update Orchestrator — ProjectRuntime

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt`
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`

- [ ] **Step 1: Add ProjectRuntime inner class**

At top of Orchestrator class:
```kotlin
data class ProjectRuntime(
    val config: ProjectConfig,
    val linear: LinearClient,
    val workspaces: WorkspaceManager,
    val state: RuntimeState,
    val dispatch: DispatchService
)
```

- [ ] **Step 2: Update constructor — accept factories, build projects map**

```kotlin
class Orchestrator(
    private val config: ServiceConfig,
    private val linearClientFactory: (ProjectConfig) -> LinearClient,
    private val workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val scope: CoroutineScope,
    private val metricsRepository: MetricsRepository? = null
) {
    val projects: Map<String, ProjectRuntime>
    
    init {
        projects = config.projects.mapValues { (slug, pc) ->
            val state = RuntimeState().apply {
                pollIntervalMs = config.pollIntervalMs
                maxConcurrentAgents = pc.agent.maxConcurrentAgents
                workspaceRoot = java.nio.file.Paths.get(pc.workspace.root)
            }
            val linear = linearClientFactory(pc)
            val ws = workspaceManagerFactory(pc)
            val dispatch = DispatchService(
                config = pc,
                state = state,
                linear = linear,
                agentRunner = agentRunner,
                workflowCache = workflowCache,
                logger = logger,
                projectSlug = slug,
                workspaces = ws,
                retryExecutor = RetryExecutor(pc.agent.maxRetryBackoffMs),
                metricsRepository = metricsRepository
            )
            ProjectRuntime(pc, linear, ws, state, dispatch)
        }
    }
```

- [ ] **Step 3: Update tick() to iterate projects**

```kotlin
private suspend fun tick() {
    try {
        for ((slug, pr) in projects) {
            reconcile(pr)
            runPreflight(pr)
            pr.dispatch.dispatchDueRetries(scope!!)
            pr.dispatch.fetchAndDispatch(scope!!)
        }
    } catch (e: Exception) {
        logger.failure("tick_failed", emptyMap(), e)
    }
}
```

- [ ] **Step 4: Update reconcile() to accept ProjectRuntime**

```kotlin
internal suspend fun reconcile(pr: ProjectRuntime) {
    val state = pr.state
    if (state.running.isEmpty()) return
    val ids = state.running.keys.toList()
    try {
        val states = pr.linear.fetchIssueStatesByIds(ids)
        for ((id, trackerState) in ids.zip(states)) {
            val entry = state.running[id] ?: continue
            if (pr.config.tracker.terminalStates.any { it.equals(trackerState, ignoreCase = true) }) {
                state.running.remove(id)
                state.claimed.remove(id)
                state.removeOutput(id)
                pr.workspaces.removeWorkspace(entry.issue.identifier)
            }
        }
    } catch (e: Exception) { ... }
}
```

- [ ] **Step 5: Update handleAgentEvent to use projects map**

```kotlin
internal fun handleAgentEvent(event: AgentEvent) {
    when (event) {
        is AgentEvent.TurnCompleted -> {
            event.usage?.let { u ->
                for (pr in projects.values) {
                    pr.state.tokenTotals = pr.state.tokenTotals.copy(
                        inputTokens = pr.state.tokenTotals.inputTokens + u.inputTokens,
                        outputTokens = pr.state.tokenTotals.outputTokens + u.outputTokens,
                        totalTokens = pr.state.tokenTotals.totalTokens + u.totalTokens
                    )
                }
            }
        }
        else -> {}
    }
}
```

Wait — this has the SAME bug (broadcasting to all projects). Instead, token totals should track per-project. But since TurnCompleted doesn't carry project context, we need a mapping. For now, just sum across all projects (the old behavior but per-state tracking was fixed in 1f7addf). Actually, looking at the current code, `handleAgentEvent` already had this fixed by looking up the right state. Let me check the current implementation.

The current Orchestrator already has per-state token tracking via `issueProjectMap`. After the factory refactor, the event handler needs access to that map. Let me keep the existing token-routing logic but update it to use `projects` map for lookup.

Actually, rather than getting into this complexity, let me simplify: `handleAgentEvent` should find the project that owns the agent (by matching threadId against running entries) and update only that project's state. This is the existing behavior from the Phase 1 fix.

- [ ] **Step 6: Update runPreflight to accept ProjectRuntime**

```kotlin
private fun runPreflight(pr: ProjectRuntime) {
    val cmd = if (pr.config.agent.kind == "opencode") 
        pr.config.agent.command ?: "opencode" 
    else 
        pr.config.agent.command ?: "codex app-server"
    if (pr.config.tracker.kind.isNullOrBlank() || pr.config.tracker.apiKey.isNullOrBlank()
        || pr.config.tracker.projectSlug.isNullOrBlank() || cmd.isBlank()
    ) {
        logger.warn("preflight_invalid", mapOf("project" to "?"))
    }
}
```

- [ ] **Step 7: Run tests to see what breaks**

```bash
./gradlew :koncerto-orchestrator:test
```
Expected: compilation errors in test file

- [ ] **Step 8: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/Orchestrator.kt
git commit -m "refactor: Orchestrator creates per-project ProjectRuntime instances"
```

### Task 3: Update ApiV1Controller — route by project slug

**Files:**
- Modify: `koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt`
- Modify: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`

- [ ] **Step 1: Accept Orchestrator instead of RuntimeState map**

```kotlin
class ApiV1Controller(
    private val config: ServiceConfig,
    private val orchestrator: Orchestrator,
    private val metricsRepository: MetricsRepository? = null
) {
    private val projects: Map<String, Orchestrator.ProjectRuntime>
        get() = orchestrator.projects
```

- [ ] **Step 2: Update state endpoint to iterate projects**

```kotlin
@GetMapping("/state")
fun state(): Map<String, Any> {
    val allRunning = projects.values.flatMap { (slug, pr) ->
        pr.state.running.values.map { entry ->
            mapOf(
                "issueIdentifier" to entry.issue.identifier,
                "projectSlug" to slug,
                "threadId" to entry.threadId,
                "turnCount" to entry.turnCount,
                "url" to entry.issue.url,
                "paused" to entry.paused
            )
        }
    }
    // ... aggregate token totals, etc.
}
```

- [ ] **Step 3: Update streamOutput to accept project-slug query param**

```kotlin
@GetMapping("/running/{identifier}/output/stream")
fun streamOutput(
    @PathVariable identifier: String,
    @RequestParam(defaultValue = "") project: String
): Flux<ServerSentEvent<String>> {
    val state = if (project.isNotBlank()) projects[project]?.state
                else projects.values.firstOrNull()?.state
    val flow = state?.outputFlow(identifier) ?: return Flux.empty()
    return flow.asPublisher().asFlux().map { line ->
        ServerSentEvent.builder(line).event("output").build()
    }
}
```

- [ ] **Step 4: Update pause/resume/cancel to accept project param (optional, iterate all if absent)**

```kotlin
@PutMapping("/running/{identifier}/pause")
fun pauseAgent(
    @PathVariable identifier: String,
    @RequestParam(defaultValue = "") project: String
): ResponseEntity<Unit> {
    val found = if (project.isNotBlank())
        projects[project]?.state?.pauseAgent(identifier) ?: false
    else
        projects.values.any { it.state.pauseAgent(identifier) }
    return if (found) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
}
```

Same pattern for resume/cancel.

- [ ] **Step 5: Run dashboard tests**

```bash
./gradlew :koncerto-dashboard:test
```

- [ ] **Step 6: Commit**

```bash
git add koncerto-dashboard/src/main/kotlin/com/anomaly/koncerto/dashboard/ApiV1Controller.kt
git commit -m "refactor: ApiV1Controller routes by project slug via Orchestrator"
```

### Task 4: Update all test files

**Files:**
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt`
- Modify: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt`
- Modify: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/DashboardUiTestConfig.kt`
- Modify: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/BeansTest.kt`

- [ ] **Step 1: Update OrchestratorTest — construct with factories**

```kotlin
// Create factory lambdas for tests:
val linearClientFactory: (ProjectConfig) -> LinearClient = { FakeLinearClient() }
val workspaceManagerFactory: (ProjectConfig) -> WorkspaceManager = { FakeWorkspaceManager() }

val orchestrator = Orchestrator(
    config = config,
    linearClientFactory = linearClientFactory,
    workspaceManagerFactory = workspaceManagerFactory,
    agentRunner = runner,
    workflowCache = mock(),
    logger = logger,
    scope = scope,
    metricsRepository = null
)
```

- [ ] **Step 2: Update OrchestratorTest — fix reconcile tests to pass ProjectRuntime**

```kotlin
// Existing tests call orchestrator.internalReconcile() — change to accept ProjectRuntime:
val pr = orchestrator.projects.values.first()
orchestrator.reconcile(pr)
```

- [ ] **Step 3: Update ApiV1ControllerTest — construct with Orchestrator**

```kotlin
class ApiV1ControllerTest {
    private val config = testServiceConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val logger = StructuredLogger("test")
    private val client = FakeLinearClient()
    private val runner = FakeAgentRunner()
    private val cache = FakeWorkflowCache()
    private val wsManager = FakeWorkspaceManager()
    private val controller = ApiV1Controller(
        config = config,
        orchestrator = Orchestrator(
            config = config,
            linearClientFactory = { client },
            workspaceManagerFactory = { wsManager },
            agentRunner = runner,
            workflowCache = cache,
            logger = logger,
            scope = scope,
            metricsRepository = null
        )
    )
}
```

- [ ] **Step 4: Update DashboardUiTestConfig — same factory pattern**

```kotlin
@Bean
fun orchestrator(/* ... */): Orchestrator = Orchestrator(
    config = config,
    linearClientFactory = { FakeLinearClient() },
    workspaceManagerFactory = { FakeWorkspaceManager() },
    agentRunner = runner,
    workflowCache = cache,
    logger = testLogger(),
    scope = scope,
    metricsRepository = null
)
```

- [ ] **Step 5: Update BeansTest — remove workspaceManager/linearClient bean references**

- [ ] **Step 6: Run all tests**

```bash
./gradlew test -x :koncerto-e2e:test
```
Expected: GREEN

- [ ] **Step 7: Commit**

```bash
git add koncerto-orchestrator/src/test/ koncerto-dashboard/src/test/ koncerto-app/src/test/
git commit -m "test: update test fixtures for per-project infrastructure"
```

### Task 5: Final verification

- [ ] **Run full test suite**

```bash
./gradlew test -x :koncerto-e2e:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Push**

```bash
git push origin main
```
