# Per-Project Infrastructure Wiring

## Summary

Fix the multi-project runtime by moving per-project infrastructure (LinearClient,
WorkspaceManager) from singleton Spring beans to Orchestrator-owned instances.

## Problem

`Beans.kt` creates single `workspaceManager` and `linearClient` beans using
`firstProjectConfig()`. All projects share one workspace root and one Linear API key.
Multi-project dispatch will route all issues through the first project's tracker and
workspace, making multi-project non-functional.

## Design

`Orchestrator` takes factory lambdas and creates per-project infrastructure internally:

```
Beans.kt              Orchestrator               ProjectRuntime
─────────             ────────────               ──────────────
config ──────────────→ projects: Map<String,     pc: ProjectConfig
linearClientFactory ─→   ProjectRuntime>          linear: LinearClient
workspaceManagerFact─→                            workspaces: WorkspaceManager
agentRunner ─────────→                           state: RuntimeState
metricsRepo ─────────→                           dispatch: DispatchService
```

### Orchestrator.ProjectRuntime

```kotlin
data class ProjectRuntime(
    val config: ProjectConfig,
    val linear: LinearClient,
    val workspaces: WorkspaceManager,
    val state: RuntimeState,
    val dispatch: DispatchService
)
```

### Beans.kt factory lambdas

```kotlin
@Bean
fun linearClientFactory(): (ProjectConfig) -> LinearClient = { pc ->
    val graphql = LinearGraphQLClient(pc.tracker.endpoint, pc.tracker.apiKey)
    DefaultLinearClient(graphql, pc.tracker.projectSlug)
}

@Bean
fun workspaceManagerFactory(
    config: ServiceConfig,
    logger: StructuredLogger
): (ProjectConfig) -> WorkspaceManager = { pc ->
    val executor = ShellHookExecutor(config.hooks.timeoutMs, logger)
    WorkspaceManager(Paths.get(pc.workspace.root), executor)
}
```

### Orchestrator construction

```kotlin
init {
    this.projects = config.projects.mapValues { (slug, pc) ->
        val state = RuntimeState().apply {
            pollIntervalMs = config.pollIntervalMs
            maxConcurrentAgents = pc.agent.maxConcurrentAgents
            workspaceRoot = Paths.get(pc.workspace.root)
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

### ApiV1Controller changes

The controller no longer receives `runtimeStates: Map<String, RuntimeState>` directly.
Instead, it receives the `Orchestrator` (or a `ProjectRuntime` map) and routes by project
slug. All endpoints that need per-project state iterate `orchestrator.projects`.

## Files

| File | Change |
|------|--------|
| `koncerto-app/.../Beans.kt` | Remove `workspaceManager()` / `linearClient()` beans; add factory lambdas |
| `koncerto-orchestrator/.../Orchestrator.kt` | Add `ProjectRuntime` inner class; build in `init`; expose `projects` map |
| `koncerto-dashboard/.../ApiV1Controller.kt` | Accept `Orchestrator`, route by project slug |
| `koncerto-orchestrator/.../DispatchService.kt` | Constructor matches new per-project pattern (already mostly there) |
| All test files | Update Orchestrator construction |
