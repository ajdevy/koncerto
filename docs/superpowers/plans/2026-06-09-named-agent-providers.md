# Named Agent Providers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add named agent providers (project-level `agents:` map) with per-issue label overrides (`agent:X`, `model:Y`), and raise coverage to 100% on modified code.

**Architecture:** New `AgentProviderConfig` data class. `AgentProjectConfig.agents` map. `StageAgentConfig.agent` reference field. `DispatchService.resolveAgent()` pipeline resolves provider + label overrides. Backward compat via `agentKind` fallback.

**Tech Stack:** Kotlin, kotlinx.serialization, JaCoCo

---

### Task 1: Add AgentProviderConfig data class and update related models

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt` — add `AgentProviderConfig`, add `agent` field to `StageAgentConfig`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt` — add `agents` field to `AgentProjectConfig`

- [ ] **Step 1: Read current files**

```bash
cat koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt
cat koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt
```

- [ ] **Step 2: Add `AgentProviderConfig` data class to ServiceConfig.kt**

After `HooksConfig`, before `StageAgentConfig`:
```kotlin
data class AgentProviderConfig(
    val kind: String,
    val command: String? = null,
    val model: String? = null,
    val maxConcurrent: Int? = null
)
```

- [ ] **Step 3: Add `agent` field to `StageAgentConfig`**

```kotlin
data class StageAgentConfig(
    val prompt: String?,
    val model: String?,
    val maxConcurrent: Int?,
    val agentKind: String?,
    val command: String?,
    val onCompleteState: String?,
    val agent: String? = null
)
```

- [ ] **Step 4: Add `agents` field to `AgentProjectConfig` in ProjectConfig.kt**

```kotlin
data class AgentProjectConfig(
    val kind: String = "opencode",
    val command: String? = null,
    val maxConcurrentAgents: Int = 2,
    val maxTurns: Int = 20,
    val maxRetryBackoffMs: Long = 300000,
    val maxConcurrentAgentsByState: Map<String, Int> = emptyMap(),
    val turnTimeoutMs: Long = 3600000,
    val readTimeoutMs: Long = 5000,
    val stallTimeoutMs: Long = 300000,
    val stages: Map<String, StageAgentConfig> = emptyMap(),
    val agents: Map<String, AgentProviderConfig> = emptyMap()
)
```

- [ ] **Step 5: Compile to check**

```bash
./gradlew :koncerto-core:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt
git commit -m "feat: add AgentProviderConfig and agent reference on StageAgentConfig"
```

### Task 2: Update config parsing for `agents:` and `agent:` fields

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt` — update `parseAgentConfig()` and `parseStages()`
- Test: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

- [ ] **Step 1: Read current `parseAgentConfig` and `parseStages`**

- [ ] **Step 2: Update `parseAgentConfig()` — parse `agents:` map**

After `stages = parseStages(map)` line, add:
```kotlin
val agents = parseAgentProviders(map)
```

Add new method:
```kotlin
internal fun parseAgentProviders(agentMap: Map<*, *>?): Map<String, AgentProviderConfig> {
    val raw = agentMap?.get("agents") as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (k, v) ->
        val name = (k as? String)?.lowercase() ?: return@mapNotNull null
        val providerMap = v as? Map<*, *> ?: return@mapNotNull null
        val kind = (providerMap["kind"] as? String)?.lowercase()
            ?: throw IllegalStateException("agent provider '$name' missing required 'kind'")
        name to AgentProviderConfig(
            kind = kind,
            command = providerMap["command"] as? String,
            model = providerMap["model"] as? String,
            maxConcurrent = (providerMap["max_concurrent"] as? Number)?.toInt()
        )
    }.toMap()
}
```

Add `agents` to the `AgentProjectConfig` constructor call in `parseAgentConfig`:
```kotlin
return AgentProjectConfig(
    ...
    stages = stages,
    agents = agents
)
```

- [ ] **Step 3: Update `parseStages()` — parse `agent:` field**

Add to the `StageAgentConfig` constructor call:
```kotlin
agent = (stageMap["agent"] as? String)?.lowercase()
```

- [ ] **Step 4: Write config parsing tests**

Add to `ServiceConfigTest`:

```kotlin
@Test
fun `parse agent providers from config`() {
    val map = mapOf<String, Any?>(
        "kind" to "opencode",
        "agents" to mapOf(
            "fast" to mapOf("kind" to "codex", "model" to "claude-sonnet-4"),
            "thorough" to mapOf("kind" to "opencode", "command" to "opencode-custom")
        )
    )
    val config = ServiceConfig.parseAgentConfig(map)
    assertThat(config.agents).hasSize(2)
    assertThat(config.agents["fast"]?.kind).isEqualTo("codex")
    assertThat(config.agents["fast"]?.model).isEqualTo("claude-sonnet-4")
    assertThat(config.agents["thorough"]?.kind).isEqualTo("opencode")
    assertThat(config.agents["thorough"]?.command).isEqualTo("opencode-custom")
}

@Test
fun `parse stage agent reference`() {
    val map = mapOf<String, Any?>(
        "kind" to "opencode",
        "stages" to mapOf(
            "implement" to mapOf(
                "agent" to "fast",
                "prompt" to "do it"
            )
        )
    )
    val config = ServiceConfig.parseAgentConfig(map)
    assertThat(config.stages["implement"]?.agent).isEqualTo("fast")
}

@Test
fun `parse empty agents defaults to empty map`() {
    val map = mapOf<String, Any?>("kind" to "opencode")
    val config = ServiceConfig.parseAgentConfig(map)
    assertThat(config.agents).isEmpty()
}

@Test
fun `parse stage without agent reference defaults null`() {
    val map = mapOf<String, Any?>(
        "kind" to "opencode",
        "stages" to mapOf(
            "implement" to mapOf("prompt" to "do it")
        )
    )
    val config = ServiceConfig.parseAgentConfig(map)
    assertThat(config.stages["implement"]?.agent).isNull()
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :koncerto-core:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add koncerto-core/
git commit -m "feat: parse named agent providers from config"
```

### Task 3: Implement resolveAgent() in DispatchService

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`

- [ ] **Step 1: Read current DispatchService.dispatch() (lines 84-142)**

- [ ] **Step 2: Add `ResolvedAgent` data class**

Before `DispatchService` class or inside it:
```kotlin
data class ResolvedAgent(
    val kind: String,
    val command: String?,
    val model: String?
)
```

- [ ] **Step 3: Add `resolveAgent()` method**

```kotlin
private fun resolveAgent(issue: Issue, stageConfig: StageAgentConfig?): ResolvedAgent {
    // Step 1: Resolve base agent from stage (via named provider or inline fields)
    val stageProvider = stageConfig?.agent?.let { projectConfig.agents[it] }

    val baseKind = stageProvider?.kind
        ?: stageConfig?.agentKind
        ?: projectConfig.agent.kind
    val baseCommand = stageProvider?.command
        ?: stageConfig?.command
        ?: projectConfig.agent.command
    val baseModel = stageProvider?.model
        ?: stageConfig?.model

    // Step 2: Issue label overrides
    val labelProvider = issue.labels.firstNotNullOfOrNull { label ->
        val prefix = "agent:"
        if (label.startsWith(prefix)) projectConfig.agents[label.removePrefix(prefix)] else null
    }

    val finalKind = labelProvider?.kind ?: baseKind
    val finalCommand = labelProvider?.command ?: baseCommand

    val labelModel = issue.labels.firstNotNullOfOrNull { label ->
        val prefix = "model:"
        if (label.startsWith(prefix)) label.removePrefix(prefix) else null
    }
    val finalModel = labelModel ?: labelProvider?.model ?: baseModel

    if (labelProvider == null && stageConfig?.agent != null && projectConfig.agents[stageConfig.agent] == null) {
        logger.warn("agent_provider_not_found", mapOf(
            "agent_name" to stageConfig.agent,
            "project_slug" to projectSlug
        ))
    }

    return ResolvedAgent(finalKind, finalCommand, finalModel)
}
```

- [ ] **Step 4: Update `dispatch()` to use `resolveAgent()`**

Replace lines 93-101:
```kotlin
val stageConfig = projectConfig.agent.stages[issue.normalizedState]
val prompt = stageConfig?.prompt ?: workflowCache.current().promptTemplate
val agentKind = stageConfig?.agentKind ?: projectConfig.agent.kind
val command = stageConfig?.command ?: projectConfig.agent.command
val model = resolveModel(issue, stageConfig)
```

With:
```kotlin
val stageConfig = projectConfig.agent.stages[issue.normalizedState]
val prompt = stageConfig?.prompt ?: workflowCache.current().promptTemplate
val resolved = resolveAgent(issue, stageConfig)
```

Then update the logger block:
```kotlin
val extra = mutableMapOf("prompt_source" to if (stageConfig?.prompt != null) "stage" else "global")
if (resolved.model != null) extra["model"] = resolved.model
if (attempt != null) extra["attempt"] = attempt.toString()
logger.info("dispatch_config", extra)
```

And the agentRunner call:
```kotlin
scope.launch {
    val result = agentRunner.run(issue, attempt, prompt, resolved.kind, resolved.command)
```

- [ ] **Step 5: Remove `resolveModel()` method** (lines 236-242) — its logic is now in `resolveAgent()`

- [ ] **Step 6: Compile check**

```bash
./gradlew :koncerto-orchestrator:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt
git commit -m "feat: resolveAgent pipeline with named providers and label overrides"
```

### Task 4: Tests for resolveAgent()

**Files:**
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

- [ ] **Step 1: Read current DispatchServiceTest to understand test patterns**

- [ ] **Step 2: Add test helper for creating DispatchService with agents**

```kotlin
private fun createDispatchServiceWithAgents(
    agents: Map<String, AgentProviderConfig> = emptyMap(),
    kind: String = "opencode",
    command: String? = null,
    stageAgentRef: String? = null
): Pair<DispatchService, RuntimeState> {
    val pc = ProjectConfig(
        tracker = TrackerConfig("linear", "http://example.com/graphql", "key", "proj"),
        workspace = WorkspaceConfig("/tmp/test"),
        agent = AgentProjectConfig(
            kind = kind,
            command = command,
            stages = mapOf(
                "in progress" to StageAgentConfig(
                    prompt = "test prompt",
                    agent = stageAgentRef,
                    agentKind = if (stageAgentRef != null) null else null,
                    onCompleteState = null,
                    model = null,
                    maxConcurrent = null,
                    command = null
                )
            ),
            agents = agents
        )
    )
    val state = RuntimeState().also { it.maxConcurrentAgents = 2 }
    val service = DispatchService(pc, state, FakeLinearClient(), FakeAgentRunner(), FakeWorkflowCache(),
        testLogger(), "test", null)
    return service to state
}
```

Wait, this approach won't work because `resolveAgent()` is `private`. I need to test it indirectly through `dispatch()` or make it `internal`.

Actually, the simplest approach is to make `resolveAgent()` internal for testing. Let me do that.

- [ ] **Step 3: Change `resolveAgent()` visibility to `internal`**

In `DispatchService.kt`, change `private fun resolveAgent` to `internal fun resolveAgent`.

- [ ] **Step 4: Write tests**

```kotlin
@Test
fun `resolveAgent returns provider kind and model from named agent on stage`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(
        agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
        stageAgentRef = "fast"
    )
    val issue = testIssue(labels = emptyList())
    val stage = service.projectConfig.agent.stages["in progress"]
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
    assertThat(resolved.model).isEqualTo("claude-sonnet-4")
}

@Test
fun `resolveAgent falls back to agentKind when no agents map`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(kind = "opencode")
    val stage = StageAgentConfig(null, null, null, "codex", null, null, null)
    val issue = testIssue(labels = emptyList())
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
}

@Test
fun `resolveAgent uses project default when no stage config`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(kind = "opencode")
    val issue = testIssue(labels = emptyList())
    val resolved = service.resolveAgent(issue, null)
    assertThat(resolved.kind).isEqualTo("opencode")
}

@Test
fun `resolveAgent label agent colon fast overrides provider`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(
        agents = mapOf(
            "fast" to AgentProviderConfig("codex", model = "claude-sonnet-4"),
            "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
        ),
        kind = "opencode",
        stageAgentRef = "slow"
    )
    val issue = testIssue(labels = listOf("agent:fast"))
    val stage = service.projectConfig.agent.stages["in progress"]
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
    assertThat(resolved.model).isEqualTo("claude-sonnet-4")
}

@Test
fun `resolveAgent label model colon gpt4o overrides stage model`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(
        agents = mapOf("fast" to AgentProviderConfig("codex", model = "claude-sonnet-4")),
        stageAgentRef = "fast"
    )
    val issue = testIssue(labels = listOf("model:gpt-4o"))
    val stage = service.projectConfig.agent.stages["in progress"]
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
    assertThat(resolved.model).isEqualTo("gpt-4o")
}

@Test
fun `resolveAgent combines agent and model labels`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(
        agents = mapOf(
            "fast" to AgentProviderConfig("codex"),
            "slow" to AgentProviderConfig("opencode", model = "claude-opus-4")
        ),
        kind = "opencode",
        stageAgentRef = "slow"
    )
    val issue = testIssue(labels = listOf("agent:fast", "model:gpt-4o"))
    val stage = service.projectConfig.agent.stages["in progress"]
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
    assertThat(resolved.model).isEqualTo("gpt-4o")
}

@Test
fun `resolveAgent handles non-existent provider label gracefully`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(kind = "opencode")
    val issue = testIssue(labels = listOf("agent:nonexistent"))
    val stage = service.projectConfig.agent.stages["in progress"]
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("opencode")
}

@Test
fun `resolveAgent backward compat with agentKind and model labels`() = runTest {
    val (service, _) = createDispatchServiceWithAgents(kind = "opencode")
    val stage = StageAgentConfig("test", null, null, "codex", null, null, null)
    val issue = testIssue(labels = listOf("model:claude-3"))
    val resolved = service.resolveAgent(issue, stage)
    assertThat(resolved.kind).isEqualTo("codex")
    assertThat(resolved.model).isEqualTo("claude-3")
}
```

Need a helper:
```kotlin
private fun testIssue(labels: List<String>): Issue = Issue(
    id = "issue-1",
    identifier = "TEST-1",
    title = "Test issue",
    description = null,
    priority = 1,
    state = "In Progress",
    branchName = null,
    url = null,
    labels = labels,
    blockedBy = emptyList(),
    createdAt = null,
    updatedAt = null
)
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :koncerto-orchestrator:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt
git add koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "test: resolveAgent tests for named providers and label overrides"
```

### Task 5: Full test suite + coverage target

- [ ] **Step 1: Run full test suite**

```bash
./gradlew test -Pjacoco -x :koncerto-e2e:test
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Check JaCoCo coverage for modified modules**

```bash
for f in koncerto-core/build/reports/jacoco/test/jacocoTestReport.xml koncerto-orchestrator/build/reports/jacoco/test/jacocoTestReport.xml; do
  module=$(echo "$f" | sed 's|.*/koncerto-\([^/]*\)/build/.*|\1|')
  pct=$(grep -o 'counter type="INSTRUCTION"[^>]*' "$f" | tail -1 | grep -oE 'missed="[0-9]+" covered="[0-9]+"' | awk -F'"' '{printf "%.1f%%", $4*100/($2+$4)}')
  echo "$module: $pct"
done
```

Expected: core > 90%, orchestrator > 85%

- [ ] **Step 3: Push**

```bash
git push origin main
```
