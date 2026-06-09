# Per-Stage Agent Configuration

## Summary

Extend the WORKFLOW.md YAML front matter with an `agent.stages` section that maps
Linear issue states to agent profiles. Each profile can define its own prompt
template path, model, agent runtime kind, command, concurrency cap, and target
completion state. Issue labels with a `model:*` prefix override the stage's model.

This enables BMAD-style multi-agent workflows where different states (Todo,
In Review, QA) use different agent personas (developer, reviewer, tester) with
different prompts and model selections.

## Motivation

The current system has a single `agent.kind` and global `promptTemplate` — all
issues are dispatched identically regardless of their state. This limits the
workflow to one agent persona per project, when real development flows need:

1. **Todo** → A developer agent with a "implement this" prompt using a fast
   model (e.g., Claude Sonnet).
2. **In Review** → A reviewer agent with a "review this code" prompt using a
   higher-quality model (e.g., Claude Opus).
3. **QA** → A tester agent with a "test this feature" prompt.

Each stage should be able to:
- Use a different prompt template tailored to the task.
- Select a different model (cheaper/faster for simple tasks, smarter for
  complex ones).
- Use a different agent runtime (codex vs opencode) if needed.
- Cap concurrent agents per stage independently.
- Optionally transition the issue to a completion state when done.

## Scope

**In scope:**
- `agent.stages` YAML section in WORKFLOW.md front matter.
- `StageAgentConfig` data class in `ServiceConfig`.
- YAML parsing for stage definitions.
- `DispatchService` routing: issue state → stage → agent profile.
- Label-based model override (`model:*` on an issue overrides stage model).
- Backward compatibility: no `stages` section = current single-agent behavior.

**Out of scope (future):**
- Dashboard model listing endpoint (`GET /api/v1/models`).
- Automatic state transition (`on_complete_state`).
- Retry re-dispatch from retry queue.
- Workflow stage graphs / DAG definitions.

## YAML Schema

```yaml
agent:
  kind: opencode
  command: opencode
  max_concurrent_agents: 2
  max_turns: 10

  stages:
    Todo:
      prompt: prompts/implement.md
      model: anthropic/claude-sonnet-4-5
      max_concurrent: 3
      agent_kind: opencode
      command: opencode-dev
      on_complete_state: "In Review"

    "In Review":
      prompt: prompts/review.md
      model: anthropic/claude-opus-4-6
      max_concurrent: 1
      agent_kind: opencode
      on_complete_state: "Done"

    QA:
      prompt: prompts/test.md
      max_concurrent: 2
      agent_kind: opencode
      on_complete_state: "Done"
```

All fields in a stage are optional — missing fields fall back to the
corresponding global `agent.*` value (or defaults).

### Fields

| Field | Type | Default |
|-------|------|---------|
| `prompt` | String? | `global promptTemplate` |
| `model` | String? | none |
| `max_concurrent` | Int? | global `agent.max_concurrent_agents` |
| `agent_kind` | String? | global `agent.kind` |
| `command` | String? | global `agent.command` (or `codex.command` / `opencode.command` depending on kind) |
| `on_complete_state` | String? | none (no auto-transition) |

### Label-based Model Override

Issue labels with prefix `model:` override the model for that specific issue.
This takes priority over both the stage model and any global model.

Example Linear label: `model:claude-haiku-4-5`

Priority (highest wins):
1. Issue label `model:X` (case-insensitive, label is lowercased)
2. Stage `model` field
3. No model (runtime default)

## Implementation

### Files to modify

| File | Change |
|------|--------|
| `koncerto-core/.../config/ServiceConfig.kt` | Add `StageAgentConfig` data class, `stages` field, YAML parsing |
| `koncerto-agent/.../agent/AgentRunner.kt` | Add optional `agentKindOverride`/`commandOverride` params to `run()` |
| `koncerto-orchestrator/.../DispatchService.kt` | Route by state → stage profile; apply label model override |

### StageAgentConfig

```kotlin
data class StageAgentConfig(
    val prompt: String?,
    val model: String?,
    val maxConcurrent: Int?,
    val agentKind: String?,
    val command: String?,
    val onCompleteState: String?
)
```

### AgentRunner.run() signature change

```kotlin
interface AgentRunner {
    suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String? = null,
        commandOverride: String? = null
    ): EmptyResult<IllegalStateException>
}
```

`DefaultAgentRunner` uses the overrides when provided, falling back to
`config.agentKind` / `config.codexCommand` / `config.opencodeCommand`.

### DispatchService routing

```kotlin
private fun dispatch(issue: Issue, scope: CoroutineScope) {
    state.claimed.add(issue.id)
    val stageConfig = config.stages[issue.normalizedState]
    val prompt = resolvePrompt(stageConfig)
    val agentKind = stageConfig?.agentKind ?: config.agentKind
    val command = resolveCommand(agentKind, stageConfig)
    val model = resolveModel(issue, stageConfig)
    scope.launch {
        val result = agentRunner.run(issue, null, prompt, agentKind, command)
        ...
    }
}
```

### Backward Compatibility

- No `stages` in YAML → `config.stages` is empty → `dispatch()` skips stage
  lookup → uses global config → identical to current behavior.
- No `model:*` label → no model override → model field from stage or null.
- `AgentRunner` default params mean callers that don't pass overrides work
  unchanged.

## Testing

- **ServiceConfigTest**: parse stages YAML → verify `StageAgentConfig` fields.
- **DispatchServiceTest**: issue in `todo` state dispatches with stage-specific
  prompt; issue with `model:haiku` label gets model from label; `In Review`
  issue uses its own stage config.
- **AgentRunnerTest**: existing tests pass unchanged (default params).
