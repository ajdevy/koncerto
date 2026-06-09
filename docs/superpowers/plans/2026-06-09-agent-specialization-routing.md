# Agent Specialization Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable routing rules that map issues to specific agent configurations based on labels, state, or priority.

**Architecture:** `RoutingRule` data class in core config, `routingRules` field on `AgentProjectConfig`, evaluation as step 0 in `resolveAgent()` before existing logic.

**Tech Stack:** Kotlin, JUnit5, YAML config parsing

---

### Task 1: Routing Rule Config Model

**Files:**
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`
- Test: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt`

- [ ] **Step 1: Write failing test for routing rule parsing**

Add to `ServiceConfigTest.kt`:

```kotlin
@Test
fun `parseAgentConfig with routing rules`() {
    val config = ServiceConfig.parseAgentConfig(mapOf(
        "kind" to "opencode",
        "agents" to mapOf(
            "frontend-agent" to mapOf("kind" to "codex", "model" to "gpt-4"),
            "backend-agent" to mapOf("kind" to "opencode")
        ),
        "routing_rules" to listOf(
            mapOf(
                "if_label" to "frontend",
                "use_agent" to "frontend-agent",
                "priority" to 10
            ),
            mapOf(
                "if_label_prefix" to "backend:",
                "use_agent" to "backend-agent",
                "priority" to 5
            ),
            mapOf(
                "if_state" to "bug",
                "if_priority_max" to 2,
                "use_agent" to "frontend-agent",
                "priority" to 8
            )
        )
    ))
    val rules = config.routingRules
    assertEquals(3, rules.size)
    assertEquals("frontend", rules[0].ifLabel)
    assertEquals("backend:", rules[1].ifLabelPrefix)
    assertEquals("bug", rules[2].ifState)
    assertEquals(2, rules[2].ifPriorityMax)
    assertEquals("frontend-agent", rules[2].useAgent)
}

@Test
fun `parseAgentConfig routing rules sorted by priority descending`() {
    val config = ServiceConfig.parseAgentConfig(mapOf(
        "kind" to "opencode",
        "routing_rules" to listOf(
            mapOf("use_agent" to "a", "priority" to 1),
            mapOf("use_agent" to "b", "priority" to 10),
            mapOf("use_agent" to "c", "priority" to 5)
        )
    ))
    assertEquals(listOf("b", "c", "a"), config.routingRules.map { it.useAgent })
}

@Test
fun `parseAgentConfig empty routing rules`() {
    val config = ServiceConfig.parseAgentConfig(mapOf("kind" to "opencode"))
    assertTrue(config.routingRules.isEmpty())
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :koncerto-core:test --tests "*ServiceConfigTest*"
```
Expected: FAIL

- [ ] **Step 3: Add RoutingRule data class and parsing**

Add to `ProjectConfig.kt`:

```kotlin
@kotlinx.serialization.Serializable
data class RoutingRule(
    val ifLabel: String? = null,
    val ifLabelPrefix: String? = null,
    val ifState: String? = null,
    val ifPriority: Int? = null,
    val ifPriorityMax: Int? = null,
    val useAgent: String,
    val priority: Int = 0
)
```

Add `routingRules` field to `AgentProjectConfig`:

```kotlin
val routingRules: List<RoutingRule> = emptyList()
```

Add parsing to `ServiceConfig.kt` in `parseAgentConfig()`:

```kotlin
internal fun parseRoutingRules(agentMap: Map<*, *>?): List<RoutingRule> {
    val raw = agentMap?.get("routing_rules") as? List<*> ?: return emptyList()
    return raw.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val useAgent = (map["use_agent"] as? String) ?: return@mapNotNull null
        RoutingRule(
            ifLabel = map["if_label"] as? String,
            ifLabelPrefix = map["if_label_prefix"] as? String,
            ifState = map["if_state"] as? String,
            ifPriority = (map["if_priority"] as? Number)?.toInt(),
            ifPriorityMax = (map["if_priority_max"] as? Number)?.toInt(),
            useAgent = useAgent,
            priority = (map["priority"] as? Number)?.toInt() ?: 0
        )
    }.sortedByDescending { it.priority }
}
```

Call it in `parseAgentConfig()`:

```kotlin
val routingRules = parseRoutingRules(map)
// Add to the return:
routingRules = routingRules
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :koncerto-core:test --tests "*ServiceConfigTest*"
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/ServiceConfigTest.kt
git commit -m "feat: add RoutingRule config model and parsing"
```

---

### Task 2: Routing Rule Evaluation in resolveAgent()

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

- [ ] **Step 1: Write failing test for routing rule evaluation**

Add to `DispatchServiceTest.kt`:

```kotlin
@Test
fun `resolveAgent uses routing rule matching label`() {
    val projectConfigWithRouting = ProjectConfig(
        tracker = projectConfig.tracker,
        workspace = projectConfig.workspace,
        agent = projectConfig.agent.copy(
            agents = mapOf("frontend-agent" to AgentProviderConfig(kind = "codex", model = "gpt-4")),
            routingRules = listOf(
                RoutingRule(ifLabel = "frontend", useAgent = "frontend-agent", priority = 10)
            )
        )
    )
    val ds = DispatchService(
        projectConfig = projectConfigWithRouting,
        state = state, linear = linear, agentRunner = agentRunner,
        workflowCache = workflowCache, logger = logger, projectSlug = "test"
    )
    val issue = issue("a", "ENG-1", labels = listOf("frontend", "bug"))
    val resolved = ds.resolveAgent(issue, stageConfig = null)
    assertEquals("codex", resolved.kind)
    assertEquals("gpt-4", resolved.model)
}

@Test
fun `resolveAgent uses routing rule matching label prefix`() {
    val projectConfigWithRouting = ProjectConfig(
        tracker = projectConfig.tracker,
        workspace = projectConfig.workspace,
        agent = projectConfig.agent.copy(
            agents = mapOf("backend-agent" to AgentProviderConfig(kind = "opencode")),
            routingRules = listOf(
                RoutingRule(ifLabelPrefix = "backend:", useAgent = "backend-agent", priority = 5)
            )
        )
    )
    val ds = DispatchService(
        projectConfig = projectConfigWithRouting,
        state = state, linear = linear, agentRunner = agentRunner,
        workflowCache = workflowCache, logger = logger, projectSlug = "test"
    )
    val issue = issue("a", "ENG-1", labels = listOf("backend:api", "bug"))
    val resolved = ds.resolveAgent(issue, stageConfig = null)
    assertEquals("opencode", resolved.kind)
}

@Test
fun `resolveAgent falls through when no routing rule matches`() {
    val projectConfigWithRouting = ProjectConfig(
        tracker = projectConfig.tracker,
        workspace = projectConfig.workspace,
        agent = projectConfig.agent.copy(
            agents = mapOf("special" to AgentProviderConfig(kind = "codex")),
            routingRules = listOf(
                RoutingRule(ifLabel = "special-label", useAgent = "special", priority = 10)
            )
        )
    )
    val ds = DispatchService(
        projectConfig = projectConfigWithRouting,
        state = state, linear = linear, agentRunner = agentRunner,
        workflowCache = workflowCache, logger = logger, projectSlug = "test"
    )
    val issue = issue("a", "ENG-1", labels = listOf("normal"))
    val resolved = ds.resolveAgent(issue, stageConfig = null)
    assertEquals(projectConfig.agent.kind, resolved.kind)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*resolveAgent*routing*"
```
Expected: FAIL

- [ ] **Step 3: Add evaluateRoutingRules() to DispatchService**

In `DispatchService.kt`, add:

```kotlin
private fun evaluateRoutingRules(issue: Issue): ResolvedAgent? {
    for (rule in projectConfig.agent.routingRules) {
        val matches = (rule.ifLabel == null || issue.labels.any { it.equals(rule.ifLabel, ignoreCase = true) })
            && (rule.ifLabelPrefix == null || issue.labels.any { it.startsWith(rule.ifLabelPrefix, ignoreCase = true) })
            && (rule.ifState == null || issue.normalizedState.equals(rule.ifState, ignoreCase = true))
            && (rule.ifPriority == null || issue.priority == rule.ifPriority)
            && (rule.ifPriorityMax == null || (issue.priority != null && issue.priority <= rule.ifPriorityMax))
        if (!matches) continue

        val provider = projectConfig.agent.agents[rule.useAgent]
        if (provider == null) {
            logger.warn("routing_rule_agent_not_found", mapOf(
                "use_agent" to rule.useAgent,
                "issue_id" to issue.id
            ))
            continue
        }

        logger.info("routing_rule_matched", mapOf(
            "issue_id" to issue.id,
            "rule" to rule.useAgent,
            "agent_kind" to provider.kind
        ))
        return ResolvedAgent(
            kind = provider.kind,
            command = provider.command,
            model = provider.model
        )
    }
    return null
}
```

Add evaluation as step 0 in `resolveAgent()`:

```kotlin
internal fun resolveAgent(issue: Issue, stageConfig: StageAgentConfig?): ResolvedAgent {
    // Step 0: Evaluate routing rules
    val routed = evaluateRoutingRules(issue)
    if (routed != null) return routed

    // ... existing logic ...
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*resolveAgent*routing*"
```
Expected: PASS

- [ ] **Step 5: Run all existing tests**

```bash
./gradlew :koncerto-orchestrator:test
```
Expected: All pass

- [ ] **Step 6: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "feat: add routing rule evaluation to resolveAgent"
```

---

### Task 3: Routing Integration with Existing Resolution Chain

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`
- Test: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt`

- [ ] **Step 1: Write integration tests for resolution priority chain**

```kotlin
@Test
fun `stage config agent overrides routing rule`() {
    val projectConfigWithRouting = ProjectConfig(
        tracker = projectConfig.tracker,
        workspace = projectConfig.workspace,
        agent = projectConfig.agent.copy(
            agents = mapOf(
                "routed" to AgentProviderConfig(kind = "codex"),
                "staged" to AgentProviderConfig(kind = "opencode", model = "claude")
            ),
            routingRules = listOf(
                RoutingRule(ifLabel = "frontend", useAgent = "routed", priority = 10)
            ),
            stages = mapOf("todo" to StageAgentConfig(
                prompt = null, model = null, maxConcurrent = null,
                agentKind = null, command = null, onCompleteState = null,
                agent = "staged"
            ))
        )
    )
    val ds = DispatchService(
        projectConfig = projectConfigWithRouting,
        state = state, linear = linear, agentRunner = agentRunner,
        workflowCache = workflowCache, logger = logger, projectSlug = "test"
    )
    val issue = issue("a", "ENG-1", state = "Todo", labels = listOf("frontend"))
    val resolved = ds.resolveAgent(issue, stageConfig = projectConfigWithRouting.agent.stages["todo"])
    // Stage config agent should win over routing rule
    assertEquals("opencode", resolved.kind)
    assertEquals("claude", resolved.model)
}

@Test
fun `label agent prefix overrides routing rule`() {
    val projectConfigWithRouting = ProjectConfig(
        tracker = projectConfig.tracker,
        workspace = projectConfig.workspace,
        agent = projectConfig.agent.copy(
            agents = mapOf(
                "routed" to AgentProviderConfig(kind = "codex"),
                "label-agent" to AgentProviderConfig(kind = "opencode")
            ),
            routingRules = listOf(
                RoutingRule(ifLabel = "frontend", useAgent = "routed", priority = 10)
            ),
            kind = "opencode"
        )
    )
    val ds = DispatchService(
        projectConfig = projectConfigWithRouting,
        state = state, linear = linear, agentRunner = agentRunner,
        workflowCache = workflowCache, logger = logger, projectSlug = "test"
    )
    val issue = issue("a", "ENG-1", labels = listOf("frontend", "agent:label-agent"))
    val resolved = ds.resolveAgent(issue, stageConfig = null)
    // Label agent should win over routing rule
    assertEquals("opencode", resolved.kind)
}
```

- [ ] **Step 2: Run integration tests**

```bash
./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*"
```
Expected: All pass

- [ ] **Step 3: Run full test suite**

```bash
./gradlew test
```
Expected: All pass

- [ ] **Step 4: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt
git commit -m "feat: wire routing rules into resolveAgent resolution chain"
```
