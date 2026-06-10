# Subagent Workplan Decomposition — Design Spec

**Date:** 2026-06-10
**Status:** Design

## Overview

Koncerto currently dispatches a single AI agent (opencode/codex) per Linear issue. For complex issues, the agent's context window fills up and performance degrades. This spec adds **subagent workplan decomposition**: the first agent analyzes the issue and writes a workplan splitting it into focused subtasks; koncerto then dispatches a fresh agent per subtask, each with a narrow context window and a focused prompt. The workspace persists between subtask runs so code changes accumulate.

## Architecture

```
Issue picked up from Linear
        │
        ▼
Agent spawns normally — runs planner prompt
        │
        ├─ Writes _koncerto/workplan.json  OR  does nothing (no workplan = normal flow)
        │
        ▼
Koncerto detects workplan → SubtaskOrchestrator
        │
        ├─ [sequential mode] Execute subtasks one-by-one in dependency order
        │   └─ Fresh agent subprocess per subtask, workspace persists
        │
        └─ [parallel mode] Frontier-based dispatch with git branch isolation
            └─ Each subtask on its own branch, merged back on completion
```

**Key principle:** Workplan is opt-in. If the planner agent doesn't write one, koncerto behaves exactly as today. Full backward compatibility.

## Data Models

### Subtask Manifest (written by planner agent to `_koncerto/workplan.json`)

```json
{
  "issueId": "KONC-123",
  "integrationBranch": "main",
  "subtasks": [
    {
      "id": "step-1",
      "description": "Implement data model and repository",
      "prompt": "Implement the XYZ data model... (focused prompt)",
      "dependsOn": [],
      "fileScope": ["src/main/kotlin/.../Model.kt", "src/main/kotlin/.../Repository.kt"]
    },
    {
      "id": "step-2",
      "description": "Write service layer with business logic",
      "prompt": "Implement the XYZ service... (focused prompt)",
      "dependsOn": ["step-1"],
      "fileScope": ["src/main/kotlin/.../Service.kt"]
    }
  ]
}
```

### Kotlin Models (`koncerto-core/.../SubtaskManifest.kt`)

```kotlin
data class SubtaskManifest(
    val issueId: String,
    val integrationBranch: String = "main",
    val subtasks: List<SubtaskDef>
)

data class SubtaskDef(
    val id: String,
    val description: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
    val fileScope: List<String> = emptyList()
)
```

### Workplan Config (`koncerto-core/.../WorkplanConfig.kt`)

```kotlin
data class WorkplanConfig(
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val maxParallelSubagents: Int = 3
) {
    enum class ExecutionMode { SEQUENTIAL, PARALLEL }
}
```

Integrated into `ProjectConfig`:

```kotlin
data class ProjectConfig(
    // ...existing fields...
    val workplan: WorkplanConfig? = null
)
```

### Runtime State (`koncerto-orchestrator/.../RuntimeState.kt`)

```kotlin
data class SubtaskState(
    val def: SubtaskDef,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val branchName: String? = null,
    val runId: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null
)

enum class SubtaskStatus { PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED }
```

### Agent Events (`koncerto-agent/.../AgentEvent.kt`)

```kotlin
data class WorkplanReady(val manifest: SubtaskManifest, val issueId: String) : AgentEvent()
data class SubtaskStarted(val subtaskId: String, val issueId: String) : AgentEvent()
data class SubtaskCompleted(val subtaskId: String, val issueId: String) : AgentEvent()
data class SubtaskFailed(val subtaskId: String, val issueId: String, val error: String) : AgentEvent()
data class MergeConflict(val subtaskId: String, val branch: String, val issueId: String) : AgentEvent()
```

## Component Design

### WorkplanParser (`koncerto-orchestrator/.../WorkplanParser.kt`)

Parses `_koncerto/workplan.json` from the workspace. Validates:
- All subtask IDs are unique
- `dependsOn` references only existing IDs
- No circular dependencies
- At least one subtask defined
- `issueId` matches the current issue

```kotlin
class WorkplanParser(private val jsonMapper: ObjectMapper) {
    fun parse(workspacePath: Path): Result<SubtaskManifest, ParseError>
}

sealed class ParseError {
    data object NOT_FOUND : ParseError()
    data class INVALID(val detail: String) : ParseError()
}
```

### SubtaskFrontier (`koncerto-orchestrator/.../SubtaskFrontier.kt`)

Computes which subtasks are ready to run based on dependency completion.

```kotlin
class SubtaskFrontier {
    /** Returns unblocked PENDING subtasks for parallel dispatch. */
    fun compute(states: List<SubtaskState>): List<SubtaskState>
    
    /** Topological sort for sequential execution (Kahn's algorithm). */
    fun topologicalSort(states: List<SubtaskState>): List<SubtaskState>
}
```

### SubtaskOrchestrator (`koncerto-orchestrator/.../SubtaskOrchestrator.kt`)

The core orchestration service. Manages the full subtask lifecycle.

```kotlin
class SubtaskOrchestrator(
    private val agentRunner: AgentRunner,
    private val gitWorkflow: GitWorkflow,
    private val workplanParser: WorkplanParser,
    private val frontier: SubtaskFrontier,
) {
    suspend fun execute(workspacePath: Path, manifest: SubtaskManifest, config: WorkplanConfig): Flow<AgentEvent>
}
```

**Sequential mode:**
1. Compute topological order from dependency graph
2. For each subtask in order:
   - Emit `SubtaskStarted`
   - Spawn fresh agent with the subtask's focused prompt
   - Wait for completion
   - Emit `SubtaskCompleted` (or `SubtaskFailed`)
3. Done — single workspace, no branching needed

**Parallel mode:**
1. Enter dispatch loop while pending subtasks exist
2. Compute frontier (unblocked subtasks)
3. Launch up to `maxParallelSubagents - running` new subtasks
4. Each subtask:
   - Create git branch `subtask/{issueId}/{subtaskId}` from `integrationBranch`
   - Spawn fresh agent on that branch
   - On completion, merge back to `integrationBranch`
   - On merge conflict, emit `MergeConflict` and block the subtask
5. Wait for all launched subtasks, then loop

### GitWorkflow Extensions (`koncerto-workspace/.../GitWorkflow.kt`)

```kotlin
class GitWorkflow(private val git: GitOperation) {
    suspend fun createBranch(workspace: Path, branchName: String, from: String)
    suspend fun mergeBranch(workspace: Path, source: String, target: String): MergeResult
    suspend fun deleteBranch(workspace: Path, branchName: String)
}

sealed class MergeResult {
    data object SUCCESS : MergeResult()
    data object CONFLICT : MergeResult()
}
```

### DispatchService Integration (`koncerto-orchestrator/.../DispatchService.kt`)

After the initial agent spawn completes, check for a workplan:

```kotlin
suspend fun dispatchIssue(issue: Issue, config: ProjectConfig, workspace: Path) {
    val run = orchestrator.startRun(issue, config)
    
        // First: spawn planner agent with the current stage's prompt
        // The workplan instructions are embedded in the stage prompt template
        val plannerResult = agentRunner.run(
            run = run,
            prompt = renderPrompt(issue, config, stage = issue.currentStage)
        )

    // Second: check for workplan
    when (val wp = workplanParser.parse(workspace)) {
        is Result.Success -> {
            val wc = config.workplan ?: WorkplanConfig()
            subtaskOrchestrator.execute(workspace, wp.value, wc)
                .collect { event -> handleEvent(event, run) }
        }
        is Result.Failure.NOT_FOUND -> {
            // Normal single-agent flow — already complete
        }
        is Result.Failure -> {
            log.error("Invalid workplan — falling back to single-agent")
        }
    }
}
```

## Planner Prompt Pattern

The `prompts/implement.md` template should include a section instructing the agent about workplan creation:

```markdown
## Workplan (Optional)

If this task can be split into independent subtasks to save context:

1. Analyze the issue and identify 2-5 focused subtasks
2. For each subtask, write a self-contained prompt and list dependencies
3. Write the plan to `_koncerto/workplan.json`
4. Execute step-1 as your first turn
5. The orchestrator will dispatch remaining subtasks to fresh agents
```

When koncerto detects a workplan, it uses the subtask's `prompt` field verbatim for each subagent invocation.

## Configuration (WORKFLOW.md)

```yaml
agent:
  kind: opencode
  max_concurrent_agents: 2
  workplan:
    execution_mode: sequential   # "sequential" (default) or "parallel"
    max_parallel_subagents: 3
```

When `workplan` is not configured, the feature is disabled — koncerto behaves as today.

## File Change Summary

| File | Action | Responsibility |
|------|--------|---------------|
| `koncerto-core/.../SubtaskManifest.kt` | Create | Data models for manifest, subtask def, subtask state |
| `koncerto-core/.../WorkplanConfig.kt` | Create | Config model for execution mode, max parallel |
| `koncerto-core/.../ProjectConfig.kt` | Modify | Add `workplan: WorkplanConfig?` field |
| `koncerto-agent/.../AgentEvent.kt` | Modify | Add WorkplanReady, SubtaskStarted/Completed/Failed, MergeConflict |
| `koncerto-orchestrator/.../WorkplanParser.kt` | Create | Parse and validate workplan.json |
| `koncerto-orchestrator/.../SubtaskFrontier.kt` | Create | Frontier computation, topological sort |
| `koncerto-orchestrator/.../SubtaskOrchestrator.kt` | Create | Core subtask lifecycle management |
| `koncerto-orchestrator/.../DispatchService.kt` | Modify | Route to SubtaskOrchestrator when workplan detected |
| `koncerto-orchestrator/.../RuntimeState.kt` | Modify | Track subtask states per run |
| `koncerto-workspace/.../GitWorkflow.kt` | Modify | Add branch creation, merge, delete operations |
| `koncerto-app/.../Beans.kt` | Modify | Wire SubtaskOrchestrator |
| `prompts/implement.md` | Modify | Add workplan creation instructions |
| `koncerto-orchestrator/.../SubtaskOrchestratorTest.kt` | Create | Tests for sequential, parallel, merge conflict |
| `koncerto-orchestrator/.../WorkplanParserTest.kt` | Create | Parse valid/invalid/circular workplans |
| `koncerto-orchestrator/.../SubtaskFrontierTest.kt` | Create | Frontier + topological sort tests |
| `koncerto-core/.../SubtaskManifestTest.kt` | Create | Serialization/validation tests |

## Testing

| Test Class | Key Scenarios |
|------------|---------------|
| `WorkplanParserTest` | Valid JSON, missing file, empty subtasks, circular deps, duplicate IDs, mismatched issueId |
| `SubtaskFrontierTest` | Linear chain, diamond dependency, fan-out (1→N), fan-in (N→1), no deps, all blocked |
| `SubtaskOrchestratorTest` | Sequential (all complete), parallel (≤max), merge conflict detected, subtask failure mid-chain, mixed success/failure |
| `DispatchServiceIntegrationTest` | Workplan present → subtask flow, no workplan → normal flow, invalid workplan → fallback |
| `GitWorkflowTest` | Branch creation, clean merge, conflicting merge, branch deletion, nonexistent branch |

## Edge Cases

1. **No workplan written** — normal single-agent flow, zero behavior change
2. **Invalid workplan JSON** — log error, fall back to single-agent
3. **All subtasks completed but planner agent didn't finish its own work** — the planner wrote the plan and executed step-1; remaining subtasks are dispatched to fresh agents
4. **Subtask failure in sequential mode** — stop execution, mark run as failed
5. **Subtask failure in parallel mode** — cancel running sibling subtasks, mark run as failed
6. **Merge conflict** — koncerto does NOT auto-resolve; emits `MergeConflict` event, logs branch name for manual resolution
7. **Single subtask** — degenerate case, works like normal single-agent but with workplan overhead; planner should not create single-subtask workplans
8. **Deep dependency chain** — sequential mode handles naturally; parallel mode degrades to near-sequential
9. **Koncerto restart mid-workplan** — workplan lives in workspace files, but runtime state is lost; current design does not handle this (workspace files only persistence). On restart, the issue would be re-dispatched.
10. **Subprocess crash mid-subtask** — handled by existing retry/error logic; subtask is marked FAILED
