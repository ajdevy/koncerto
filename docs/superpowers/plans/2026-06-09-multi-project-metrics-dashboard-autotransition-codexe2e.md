# Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 5 features: multi-project config, stage auto-transition, persistent metrics, dashboard polish, and Codex E2E

**Architecture:** Breaking config change from flat to nested `projects:` format. Single Orchestrator with per-project configs. New `koncerto-metrics` module with SQLite. Vanilla JS + Chart.js dashboard. Parameterized E2E tests.

**Tech Stack:** Kotlin, Spring Boot, SQLite (xerial sqlite-jdbc), Chart.js, Playwright

---

## Phase Ordering

```
Phase 1: Multi-project config (breaking change) — must go first
Phase 2: Stage auto-transition — depends on Phase 1 (per-project stages)
Phase 3: Persistent metrics — independent, can parallel Phase 2
Phase 4: Dashboard polish — depends on Phase 3 (history data)
Phase 5: Codex E2E — independent, can go anytime
```

## File Map

| File | Action | Phase |
|------|--------|-------|
| `koncerto-core/.../config/ServiceConfig.kt` | Rewrite to map of project configs | 1 |
| `koncerto-core/.../config/ProjectConfig.kt` | **Create** — per-project config | 1 |
| `koncerto-core/.../config/StageAgentConfig.kt` | **Create** — extracted from ServiceConfig | 1 |
| `koncerto-core/.../model/Issue.kt` | Add `teamId: String?` field | 2 |
| `koncerto-linear/.../IssueMapper.kt` | Parse `issue.team.id` from GraphQL | 2 |
| `koncerto-orchestrator/.../Orchestrator.kt` | Accept project map | 1 |
| `koncerto-orchestrator/.../DispatchService.kt` | Per-project routing, stage lookup, pause support | 1,2,4 |
| `koncerto-orchestrator/.../RuntimeState.kt` | Per-project state, pause/cancel fields | 1,4 |
| `koncerto-agent/.../AgentRunner.kt` | Add pause check | 4 |
| `koncerto-linear/.../LinearClient.kt` | Add updateIssueState, workflowStates | 2 |
| `koncerto-linear/.../StateMapper.kt` | **Create** — state name→ID resolution | 2 |
| `koncerto-metrics/build.gradle.kts` | **Create** — new module | 3 |
| `koncerto-metrics/.../MetricsRepository.kt` | **Create** — interface + impl | 3 |
| `koncerto-metrics/.../IssueMetrics.kt` | **Create** — data class | 3 |
| `koncerto-dashboard/.../ApiV1Controller.kt` | Add 4 new endpoints | 4 |
| `koncerto-dashboard/.../dashboard.html` | Rewrite with sections + Chart.js | 4 |
| `koncerto-app/.../Beans.kt` | Wire metrics, project map | 1,3 |
| `koncerto-e2e/.../KoncertoE2eTest.kt` | Parameterize for codex | 5 |
| `settings.gradle.kts` | Include koncerto-metrics | 3 |

---

## Phase 1: Multi-Project Config

### Task 1.1: Create ProjectConfig and StageAgentConfig

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/StageAgentConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ServiceConfig.kt`

- [ ] **Step 1: Create StageAgentConfig.kt**

```kotlin
package com.anomaly.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class StageAgentConfig(
    val prompt: String? = null,
    val model: String? = null,
    val maxConcurrent: Int? = null,
    val agentKind: String? = null,
    val command: String? = null,
    val onCompleteState: String? = null
)
```

- [ ] **Step 2: Create ProjectConfig.kt**

```kotlin
package com.anomaly.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class ProjectConfig(
    val tracker: TrackerConfig,
    val workspace: WorkspaceConfig,
    val agent: AgentProjectConfig
)

@Serializable
data class TrackerConfig(
    val kind: String,
    val endpoint: String,
    val apiKey: String,
    val projectSlug: String
)

@Serializable
data class WorkspaceConfig(
    val root: String
)

@Serializable
data class AgentProjectConfig(
    val kind: String = "opencode",
    val command: String? = null,
    val maxConcurrentAgents: Int = 2,
    val maxTurns: Int = 20,
    val turnTimeoutMs: Long = 3600000,
    val stages: Map<String, StageAgentConfig> = emptyMap()
)
```

- [ ] **Step 3: Rewrite ServiceConfig.kt**

```kotlin
package com.anomaly.koncerto.core.config

import com.anomaly.koncerto.core.config.yaml.YamlConfigLoader
import kotlinx.serialization.Serializable

@Serializable
data class ServiceConfig(
    val pollIntervalMs: Long = 30000,
    val maxRetryBackoffMs: Long = 300000,
    val projects: Map<String, ProjectConfig> = emptyMap()
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): ServiceConfig {
            return YamlConfigLoader.parseServiceConfig(map)
        }
    }
}

// Keep existing fields for backward compat during migration:
// HooksConfig, GitConfig stay at module level
```

- [ ] **Step 4: Update YamlConfigLoader to parse new format**

In `YamlConfigLoader.kt`:

```kotlin
fun parseServiceConfig(map: Map<String, Any?>): ServiceConfig {
    val pollIntervalMs = (map["poll_interval_ms"] as? Number)?.toLong() ?: 30000L
    val maxRetryBackoffMs = (map["max_retry_backoff_ms"] as? Number)?.toLong() ?: 300000L
    
    val projects = mutableMapOf<String, ProjectConfig>()
    val projectsRaw = map["projects"] as? Map<*, *> ?: emptyMap<Any, Any>()
    for ((key, value) in projectsRaw) {
        val slug = key.toString()
        val p = value as? Map<*, *> ?: continue
        projects[slug] = parseProjectConfig(slug, p)
    }
    
    return ServiceConfig(
        pollIntervalMs = pollIntervalMs,
        maxRetryBackoffMs = maxRetryBackoffMs,
        projects = projects
    )
}

private fun parseProjectConfig(slug: String, map: Map<*, *>): ProjectConfig {
    val tracker = map["tracker"] as? Map<*, *> ?: error("Missing tracker for project $slug")
    val workspace = map["workspace"] as? Map<*, *> ?: error("Missing workspace for project $slug")
    val agent = map["agent"] as? Map<*, *> ?: error("Missing agent for project $slug")
    
    return ProjectConfig(
        tracker = parseTrackerConfig(slug, tracker),
        workspace = WorkspaceConfig(root = workspace["root"] as? String ?: error("Missing workspace.root for $slug")),
        agent = parseAgentProjectConfig(agent)
    )
}
```

- [ ] **Step 5: Run existing tests to see what breaks**

```bash
./gradlew test -x :koncerto-e2e:test
```

Expected: compilation errors in tests referencing old ServiceConfig fields, plus test failures.

- [ ] **Step 6: Update all test fixtures to use new config format**

This touches many files. The pattern for each test:

```kotlin
// Before:
val config = ServiceConfig(
    trackerKind = "linear",
    trackerEndpoint = "x",
    ...
)

// After:
val config = ServiceConfig(
    projects = mapOf("default" to ProjectConfig(
        tracker = TrackerConfig(kind = "linear", endpoint = "x", apiKey = "key", projectSlug = "proj"),
        workspace = WorkspaceConfig(root = "/tmp/test"),
        agent = AgentProjectConfig(kind = "opencode")
    ))
)
```

Files to update:
- `koncerto-core/.../ServiceConfigTest.kt`
- `koncerto-orchestrator/.../DispatchServiceTest.kt`
- `koncerto-orchestrator/.../OrchestratorTest.kt`
- `koncerto-orchestrator/.../RuntimeStateTest.kt`
- `koncerto-app/.../DashboardUiTestConfig.kt`
- `koncerto-app/.../BeansTest.kt`
- `koncerto-e2e/.../KoncertoE2eTest.kt`

- [ ] **Step 7: Update Orchestrator for project map**

```kotlin
class Orchestrator(
    private val config: ServiceConfig,
    private val linearClient: LinearClient,
    private val agentRunner: AgentRunner,
    private val scope: CoroutineScope
) {
    private val states = mutableMapOf<String, RuntimeState>()
    
    fun start() {
        for (slug in config.projects.keys) {
            states[slug] = RuntimeState()
        }
        scope.launch { pollLoop() }
    }
    
    private suspend fun pollLoop() {
        while (isActive) {
            for ((slug, projectConfig) in config.projects) {
                val state = states[slug]!!
                val issues = linearClient.fetchIssues(projectConfig.tracker)
                dispatchForProject(slug, projectConfig, state, issues)
            }
            delay(config.pollIntervalMs)
        }
    }
}
```

- [ ] **Step 8: Update DispatchService for project routing**

```kotlin
class DispatchService(
    private val config: ServiceConfig,
    private val states: Map<String, RuntimeState>,
    private val linearClient: LinearClient,
    private val agentRunner: AgentRunner,
    private val workspaces: WorkspaceManager? = null
) {
    fun dispatchForProject(
        projectSlug: String,
        projectConfig: ProjectConfig,
        state: RuntimeState,
        issues: List<Issue>
    ) {
        for (issue in issues) {
            if (state.claimed.contains(issue.id)) continue
            val stageConfig = projectConfig.agent.stages[issue.normalizedState]
            // ... existing dispatch logic using projectConfig.agent fields
        }
    }
}
```

- [ ] **Step 9: Update Beans.kt**

```kotlin
@Bean
fun orchestrator(
    config: ServiceConfig,
    linearClient: LinearClient,
    agentRunner: AgentRunner,
    scope: CoroutineScope
): Orchestrator {
    return Orchestrator(config, linearClient, agentRunner, scope)
}
```

- [ ] **Step 10: Run all tests and fix failures**

```bash
./gradlew test -x :koncerto-e2e:test
```

Expected: GREEN

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "feat: multi-project config with nested YAML format"
```

---

## Phase 2: Stage Auto-Transition

### Task 2.1: Add issue state update to LinearClient

**Files:**
- Modify: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/LinearClient.kt`
- Create: `koncerto-linear/src/main/kotlin/com/anomaly/koncerto/linear/StateMapper.kt`

- [ ] **Step 1: Write test for updateIssueState**

In `LinearClientTest.kt`:

```kotlin
@Test
fun `updateIssueState sends mutation and returns success`() = runTest {
    val response = """{"data":{"issueUpdate":{"success":true}}}"""
    server.stubFor(
        post(urlPathEqualTo("/graphql"))
            .withRequestBody(matchingJsonPath("$.query", containing("issueUpdate")))
            .willReturn(jsonResponse(response, 200))
    )
    val result = client.updateIssueState("issue-1", "Done")
    assertThat(result.isSuccess).isTrue()
}
```

- [ ] **Step 2: Implement updateIssueState**

```kotlin
suspend fun updateIssueState(issueId: String, stateId: String): EmptyResult<DataError> {
    val query = """
        mutation UpdateIssueState(\$id: String!, \$stateId: String!) {
            issueUpdate(id: \$id, input: { stateId: \$stateId }) { success }
        }
    """.trimIndent()
    val variables = mapOf("id" to issueId, "stateId" to stateId)
    return executeMutation(query, variables) { json ->
        val success = json["data"]?.get("issueUpdate")?.get("success")?.asBoolean ?: false
        if (success) EmptyResult.Success else EmptyResult.Failure(DataError.Unknown)
    }
}
```

- [ ] **Step 3: Add resolveStateId method**

```kotlin
suspend fun resolveStateId(teamId: String, stateName: String): String? {
    val query = """
        query WorkflowStates(\$teamId: String!) {
            team(id: \$teamId) { states { nodes { id name } } }
        }
    """.trimIndent()
    val response = executeGraphQL(query, mapOf("teamId" to teamId))
    val states = response["data"]?.get("team")?.get("states")?.get("nodes")?.asList ?: return null
    return states.firstOrNull {
        it.get("name")?.asString?.lowercase() == stateName.lowercase()
    }?.get("id")?.asString
}
```

- [ ] **Step 4: Wire auto-transition in DispatchService**

In `DispatchService.dispatch()` after agent run succeeds, resolve state name → state ID via team, then transition:

```kotlin
val onCompleteState = stageConfig?.onCompleteState
if (onCompleteState != null && onCompleteState != issue.state) {
    val teamId = issue.teamId ?: return@let // need teamId from Issue model
    scope.launch {
        val stateId = linearClient.resolveStateId(teamId, onCompleteState)
        if (stateId != null) {
            linearClient.updateIssueState(issue.id, stateId)
                .onFailure { logger.error("Failed to transition ${issue.id} to $onCompleteState", it) }
        }
    }
}
```

Note: `Issue` model needs a `teamId: String?` field added. Update `IssueMapper.kt` to parse `issue.team.id` from the GraphQL response.

- [ ] **Step 5: Run tests**

```bash
./gradlew test -x :koncerto-e2e:test
```

Expected: GREEN

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: stage auto-transition via updateIssueState"
```

---

## Phase 3: Persistent Metrics

### Task 3.1: Create koncerto-metrics module

**Files:**
- Create: `koncerto-metrics/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `koncerto-app/build.gradle.kts`
- Create: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/IssueMetrics.kt`
- Create: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/MetricsRepository.kt`
- Create: `koncerto-metrics/src/main/kotlin/com/anomaly/koncerto/metrics/SqliteMetricsRepository.kt`
- Create: `koncerto-metrics/src/test/kotlin/com/anomaly/koncerto/metrics/MetricsRepositoryTest.kt`

- [ ] **Step 1: Create build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}
```

- [ ] **Step 2: Add to settings.gradle.kts**

```kotlin
include(":koncerto-metrics")
```

Add to `koncerto-app/build.gradle.kts`:
```kotlin
implementation(project(":koncerto-metrics"))
```

- [ ] **Step 3: Create IssueMetrics data class**

```kotlin
package com.anomaly.koncerto.metrics

data class IssueMetrics(
    val issueId: String,
    val issueIdentifier: String,
    val projectSlug: String?,
    val totalRuns: Int,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalTokens: Long,
    val lastResult: String?,
    val lastRunAt: String?,
    val createdAt: String,
    val updatedAt: String
)

data class TokenDaySummary(
    val date: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long
)
```

- [ ] **Step 4: Create MetricsRepository interface**

```kotlin
package com.anomaly.koncerto.metrics

interface MetricsRepository {
    suspend fun updateAfterRun(
        issueId: String,
        issueIdentifier: String,
        projectSlug: String?,
        result: String,
        inputTokens: Long,
        outputTokens: Long,
        totalTokens: Long
    )
    suspend fun findAll(): List<IssueMetrics>
    suspend fun findByProject(projectSlug: String?): List<IssueMetrics>
    suspend fun tokenHistory(days: Int = 30): List<TokenDaySummary>
}
```

- [ ] **Step 5: Implement SqliteMetricsRepository**

```kotlin
package com.anomaly.koncerto.metrics

import com.anomaly.koncerto.core.DataError
import com.anomaly.koncerto.core.EmptyResult
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteMetricsRepository(private val dbPath: String) : MetricsRepository {
    private val lock = Any()
    
    init {
        Class.forName("org.sqlite.JDBC")
        val conn = connection()
        conn.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS issue_metrics (
                issue_id TEXT PRIMARY KEY,
                issue_identifier TEXT NOT NULL,
                project_slug TEXT,
                total_runs INTEGER DEFAULT 0,
                total_input_tokens INTEGER DEFAULT 0,
                total_output_tokens INTEGER DEFAULT 0,
                total_tokens INTEGER DEFAULT 0,
                last_result TEXT,
                last_run_at TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
        conn.close()
    }
    
    private fun connection(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    
    override suspend fun updateAfterRun(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        result: String, inputTokens: Long, outputTokens: Long, totalTokens: Long
    ) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val conn = connection()
            val now = Instant.now().toString()
            val existing = conn.prepareStatement("SELECT total_runs, total_input_tokens, total_output_tokens, total_tokens, created_at FROM issue_metrics WHERE issue_id = ?")
                .apply { setString(1, issueId) }
                .executeQuery()
            if (existing.next()) {
                val runs = existing.getInt(1) + 1
                val ins = existing.getLong(2) + inputTokens
                val outs = existing.getLong(3) + outputTokens
                val tot = existing.getLong(4) + totalTokens
                conn.prepareStatement("""
                    UPDATE issue_metrics SET total_runs=?, total_input_tokens=?, total_output_tokens=?, total_tokens=?, 
                    last_result=?, last_run_at=?, updated_at=? WHERE issue_id=?
                """).apply {
                    setInt(1, runs); setLong(2, ins); setLong(3, outs); setLong(4, tot)
                    setString(5, result); setString(6, now); setString(7, now); setString(8, issueId)
                }.executeUpdate()
            } else {
                conn.prepareStatement("""
                    INSERT INTO issue_metrics VALUES (?,?,?,1,?,?,?,?,?,?,?)
                """).apply {
                    setString(1, issueId); setString(2, issueIdentifier); setString(3, projectSlug)
                    setLong(4, inputTokens); setLong(5, outputTokens); setLong(6, totalTokens)
                    setString(7, result); setString(8, now); setString(9, now); setString(10, now)
                }.executeUpdate()
            }
            conn.close()
        }
    }
    
    // ... other methods follow same pattern
}
```

- [ ] **Step 6: Write tests**

```kotlin
class MetricsRepositoryTest {
    private val repo = SqliteMetricsRepository(":memory:")
    
    @Test
    fun `updateAfterRun creates new record`() = runTest {
        repo.updateAfterRun("1", "ABC-1", "frontend", "success", 100, 50, 150)
        val all = repo.findAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].totalRuns).isEqualTo(1)
        assertThat(all[0].totalTokens).isEqualTo(150)
    }
    
    @Test
    fun `updateAfterRun accumulates on subsequent calls`() = runTest {
        repo.updateAfterRun("1", "ABC-1", null, "success", 100, 50, 150)
        repo.updateAfterRun("1", "ABC-1", null, "success", 200, 100, 300)
        val all = repo.findAll()
        assertThat(all[0].totalRuns).isEqualTo(2)
        assertThat(all[0].totalTokens).isEqualTo(450)
    }
    
    @Test
    fun `findByProject filters correctly`() = runTest {
        repo.updateAfterRun("1", "ABC-1", "frontend", "success", 100, 50, 150)
        repo.updateAfterRun("2", "ABC-2", "backend", "failure", 50, 25, 75)
        assertThat(repo.findByProject("frontend")).hasSize(1)
        assertThat(repo.findByProject("backend")).hasSize(1)
        assertThat(repo.findByProject(null)).hasSize(0)
    }
}
```

- [ ] **Step 7: Wire in DispatchService**

Inject `MetricsRepository` into `DispatchService`. In `dispatch()` after agent run:

```kotlin
// After agentRunner.run() completes (success or failure):
val result = agentRunner.run(...)
result
    .onSuccess {
        metricsRepository.updateAfterRun(
            issueId = issue.id, issueIdentifier = issue.identifier,
            projectSlug = projectSlug, result = "success",
            inputTokens = tokens.input, outputTokens = tokens.output,
            totalTokens = tokens.input + tokens.output
        )
    }
    .onFailure { error ->
        metricsRepository.updateAfterRun(
            issueId = issue.id, issueIdentifier = issue.identifier,
            projectSlug = projectSlug, result = "failure",
            inputTokens = 0, outputTokens = 0, totalTokens = 0
        )
    }
```

- [ ] **Step 8: Wire bean in Beans.kt**

```kotlin
@Bean
fun metricsRepository(@Value("\${koncerto.db.path:${'$'}{user.home}/.koncerto/metrics.db}") dbPath: String): MetricsRepository {
    val dir = Paths.get(dbPath).parent
    if (dir != null) Files.createDirectories(dir)
    return SqliteMetricsRepository(dbPath)
}
```

- [ ] **Step 9: Run tests**

```bash
./gradlew :koncerto-metrics:test
```

Expected: GREEN

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: persistent metrics module with SQLite"
```

---

## Phase 4: Dashboard Polish

### Task 4.1: Add API endpoints

**Files:**
- Modify: `koncerto-dashboard/.../ApiV1Controller.kt`
- Modify: `koncerto-dashboard/.../dashboard.html`
- Modify: `koncerto-orchestrator/.../RuntimeState.kt`

- [ ] **Step 1: Add pause/cancel fields to RunningEntry**

```kotlin
data class RunningEntry(
    // ... existing fields ...
    val paused: Boolean = false,
    val cancelled: Boolean = false
)
```

- [ ] **Step 2: Add endpoints to ApiV1Controller**

```kotlin
@GetMapping("/history")
suspend fun history(
    @RequestParam project: String? = null,
    @RequestParam state: String? = null,
    @RequestParam(defaultValue = "50") limit: Int = 50
): List<IssueMetrics> {
    return if (project != null) metricsRepository.findByProject(project)
           else metricsRepository.findAll()
}

@GetMapping("/stages")
fun stages(): Map<String, Any> {
    return config.projects.mapValues { (slug, pc) ->
        mapOf(
            "agent" to mapOf("kind" to pc.agent.kind, "maxConcurrent" to pc.agent.maxConcurrentAgents),
            "stages" to pc.agent.stages.mapValues { (_, sc) ->
                mapOf("maxConcurrent" to sc.maxConcurrent, "onCompleteState" to sc.onCompleteState)
            }
        )
    }
}

@PutMapping("/running/{identifier}/pause")
suspend fun pauseAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
    return if (runtimeState.pauseAgent(identifier)) ResponseEntity.ok().build()
           else ResponseEntity.notFound().build()
}

@PutMapping("/running/{identifier}/resume")
suspend fun resumeAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
    return if (runtimeState.resumeAgent(identifier)) ResponseEntity.ok().build()
           else ResponseEntity.notFound().build()
}

@PutMapping("/running/{identifier}/cancel")
suspend fun cancelAgent(@PathVariable identifier: String): ResponseEntity<Unit> {
    return if (runtimeState.cancelAgent(identifier)) ResponseEntity.ok().build()
           else ResponseEntity.notFound().build()
}
```

- [ ] **Step 3: Rewrite dashboard.html**

The HTML gets new sections. Outline:

```html
<!DOCTYPE html>
<html>
<head>
    <title>Koncerto Dashboard</title>
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>...</style>
</head>
<body>
    <nav>Running | History | Concurrency | Token Usage</nav>
    <section id="running"><!-- existing running table + controls --></section>
    <section id="history"><!-- sortable table from /api/v1/history --></section>
    <section id="concurrency"><!-- per-project bars from /api/v1/stages --></section>
    <section id="tokens"><!-- Chart.js canvas --></section>
    <script>
        // Existing refresh() + toggleOutput() + outputSources logic
        // New: loadHistory(), loadStages(), loadTokenChart()
        // Chart.js line chart from /api/v1/history aggregated by day
    </script>
</body>
</html>
```

- [ ] **Step 4: Update Playwright tests**

Add tests for:
- History section loads data
- Concurrency section shows per-project bars
- Token chart canvas renders

- [ ] **Step 5: Run all tests**

```bash
./gradlew test -x :koncerto-e2e:test
```

- [ ] **Step 6: Commit**

```bash
git commit -m "feat: enhanced dashboard with history, charts, and agent controls"
```

---

## Phase 5: Codex E2E

### Task 5.1: Parameterize E2E test

**Files:**
- Modify: `koncerto-e2e/src/test/kotlin/com/anomaly/koncerto/e2e/KoncertoE2eTest.kt`

- [ ] **Step 1: Parameterize the existing test**

```kotlin
class KoncertoE2eTest {
    companion object {
        @JvmStatic
        fun agentKinds(): List<String> = buildList {
            add("opencode")
            if (System.getProperty("koncerto.e2e.codex", "false").toBoolean()) add("codex")
        }
    }
    
    @ParameterizedTest
    @ValueSource(strings = ["opencode", "codex"])
    fun `end to end agent run`(agentKind: String) {
        if (agentKind == "codex" && !System.getProperty("koncerto.e2e.codex", "false").toBoolean()) {
            assumeTrue(false, "Skipping Codex E2E: set -Dkoncerto.e2e.codex=true")
        }
        // rest of test uses agentKind to configure the ServiceConfig
    }
}
```

- [ ] **Step 2: Run both paths**

```bash
./gradlew :koncerto-e2e:test -Dkoncerto.e2e.opencode=true -Dkoncerto.e2e.codex=true
```

Expected: Both paths pass

- [ ] **Step 3: Commit**

```bash
git commit -m "feat: parameterize E2E test for Codex runtime"
```

---

## Final Verification

- [ ] **Run full build**

```bash
./gradlew clean build -x :koncerto-e2e:test
./gradlew :koncerto-e2e:test -Dkoncerto.e2e.opencode=true
```

Expected: BUILD SUCCESSFUL

- [ ] **Push**

```bash
git push origin main
```
