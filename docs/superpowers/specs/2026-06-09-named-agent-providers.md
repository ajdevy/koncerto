# Named Agent Providers with Issue Label Overrides

## Summary

Add named agent providers (`agents:` map at project level) referenced by stages via
`agent:` field. Issue labels (`agent:X`, `model:Y`) override per-issue. Backward
compatible — existing `agentKind` / `command` / `model` per-stage fields still work.

## Config Model

### New: AgentProviderConfig

```kotlin
data class AgentProviderConfig(
    val kind: String,               // "opencode" | "codex"
    val command: String? = null,    // overrides default command for this kind
    val model: String? = null,      // model string for this provider
    val maxConcurrent: Int? = null  // reserved, not yet wired into capacity
)
```

### Changed: AgentProjectConfig

New field `agents: Map<String, AgentProviderConfig> = emptyMap()`.

### Changed: StageAgentConfig

New optional field `agent: String? = null` — key into `agents:` map.

## Resolution Pipeline

```
resolveAgent(issue, stageConfig):
  stageProvider = stageConfig.agent → projectConfig.agents[name]
  
  baseKind    = stageProvider?.kind  ?: stageConfig?.agentKind  ?: projectConfig.agent.kind
  baseCommand = stageProvider?.command ?: stageConfig?.command ?: projectConfig.agent.command
  baseModel   = stageProvider?.model ?: stageConfig?.model
  
  labelProvider = issue.labels match "agent:<name>" → projectConfig.agents[name]
  
  finalKind    = labelProvider?.kind  ?: baseKind
  finalCommand = labelProvider?.command ?: baseCommand
  
  labelModel = issue.labels match "model:<name>" → the name
  finalModel = labelModel ?: labelProvider?.model ?: baseModel
  
  return ResolvedAgent(finalKind, finalCommand, finalModel)
```

## Config Parsing Changes

`parseAgentConfig()`:
- Parse `agents:` map from agent config into `Map<String, AgentProviderConfig>`.
- Each agent entry: `kind` (required), `command`, `model`, `max_concurrent`.
- `kind` lowercased for consistency.

`parseStages()`:
- New field `agent:` parsed as the stage's agent reference.

## DispatchService Changes

Replace inline agent resolution (lines 95-97):
```kotlin
val agentKind = stageConfig?.agentKind ?: projectConfig.agent.kind
val command = stageConfig?.command ?: projectConfig.agent.command
val model = resolveModel(issue, stageConfig)
```

With:
```kotlin
val resolved = resolveAgent(issue, stageConfig)
```

Where `resolveAgent()` implements the pipeline above (combining provider lookup,
backward compat fallback, and label overrides for both `agent:X` and `model:Y`).

`resolveModel()` (line 236) is merged into `resolveAgent()` — the `model:X` label
handling is part of the resolution pipeline, not a separate method.

## Backward Compatibility

Configs without `agents:` and without `agent:` on stages work identically to today:
- `stageConfig.agentKind` / `stageConfig.command` / `stageConfig.model` resolve
- Falls back to `projectConfig.agent.kind` / `.command`
- `model:` labels still override model

Configs using both `agents:` AND `agentKind` on a stage: `agentKind` is ignored
if `agent:` is set (the named provider defines kind).

## Tests

- Parse config with `agents:` and verify fields
- Parse config with `agent:` on stage referencing a named agent
- `resolveAgent` returns provider's kind/command/model for a named agent
- `resolveAgent` falls back to `agentKind` when no `agents:` map
- `resolveAgent` overrides provider with `agent:X` label
- `resolveAgent` overrides model with `model:X` label
- `resolveAgent` combines `agent:X` and `model:X` labels
- `resolveAgent` handles agent label referencing non-existent provider (log warning, fall back)
- Existing configs work unchanged (backward compat)
- No `agent:` and no `agentKind` → falls through to project default kind
- Existing agent kind override via stage commands integrated with new `agents`
