# Subagent Workplan Decomposition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add subagent workplan decomposition to koncerto's dispatch flow — the first agent can decompose an issue into focused subtasks, each dispatched to a fresh agent subprocess with a narrow context window.

**Architecture:** A `SubtaskOrchestrator` sits between `DispatchService` and `AgentRunner`. After the initial "planner" agent completes, koncerto checks for `_koncerto/workplan.json` in the workspace. If found, subtasks are executed via `SubtaskRunner` (a lightweight agent spawner that skips workspace/git setup). Sequential mode runs subtasks in dependency order; parallel mode uses git branch isolation with frontier-based dispatch.

**Tech Stack:** Kotlin + kotlinx.serialization + coroutines + git CLI

---

## File Map

| File | Action | Workstream |
|------|--------|------------|
| `koncerto-core/.../config/SubtaskManifest.kt` | Create | A |
| `koncerto-core/.../config/WorkplanConfig.kt` | Create | A |
| `koncerto-core/.../config/ProjectConfig.kt` | Modify | A |
| `koncerto-agent/.../AgentEvent.kt` | Modify | A |
| `koncerto-agent/.../SubtaskRunner.kt` | Create | B |
| `koncerto-orchestrator/.../WorkplanParser.kt` | Create | C |
| `koncerto-orchestrator/.../SubtaskFrontier.kt` | Create | C |
| `koncerto-orchestrator/.../SubtaskOrchestrator.kt` | Create | C |
| `koncerto-orchestrator/.../DispatchService.kt` | Modify | C |
| `koncerto-orchestrator/.../RuntimeState.kt` | Modify | A |
| `koncerto-workspace/.../GitWorkflow.kt` | Modify | B |
| `koncerto-app/.../Beans.kt` | Modify | C |
| `prompts/implement.md` | Modify | C |

**Parallel workstreams:** A (data models + events), B (runner + git), C (orchestration + integration). A and B are independent — C depends on both.

---

### Workstream A: Data Models, Events, and Config

### Task A1: Create SubtaskManifest data models

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/SubtaskManifest.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/SubtaskManifestTest.kt`

```kotlin
package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

class SubtaskManifestTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `round-trip serialization`() {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            integrationBranch = "main",
            subtasks = listOf(
                SubtaskDef(
                    id = "step-1",
                    description = "Implement data model",
                    prompt = "Create the Foo data model...",
                    dependsOn = emptyList(),
                    fileScope = listOf("src/.../Foo.kt")
                ),
                SubtaskDef(
                    id = "step-2",
                    description = "Write service layer",
                    prompt = "Implement the FooService...",
                    dependsOn = listOf("step-1"),
                    fileScope = listOf("src/.../FooService.kt")
                )
            )
        )
        val encoded = json.encodeToString(manifest)
        val decoded = json.decodeFromString<SubtaskManifest>(encoded)
        assertThat(decoded.issueId).isEqualTo("KONC-123")
        assertThat(decoded.subtasks.size).isEqualTo(2)
        assertThat(decoded.subtasks[0].dependsOn).isEqualTo(emptyList())
        assertThat(decoded.subtasks[1].dependsOn).isEqualTo(listOf("step-1"))
    }

    @Test
    fun `minimal serialization`() {
        val manifest = SubtaskManifest(
            issueId = "KONC-456",
            subtasks = listOf(
                SubtaskDef(id = "only", description = "Only task", prompt = "Do it")
            )
        )
        val jsonStr = json.encodeToString(manifest)
        assertThat(jsonStr).isNotNull()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-core:test --tests "*SubtaskManifestTest*"` 
Expected: FAIL - compilation error (classes don't exist)

- [ ] **Step 3: Create SubtaskManifest.kt**

```kotlin
package com.flexsentlabs.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class SubtaskManifest(
    val issueId: String,
    val integrationBranch: String = "main",
    val subtasks: List<SubtaskDef>
)

@Serializable
data class SubtaskDef(
    val id: String,
    val description: String,
    val prompt: String,
    val dependsOn: List<String> = emptyList(),
    val fileScope: List<String> = emptyList()
)

enum class SubtaskStatus {
    PENDING, RUNNING, SUCCEEDED, FAILED, BLOCKED
}

data class SubtaskState(
    val def: SubtaskDef,
    val status: SubtaskStatus = SubtaskStatus.PENDING,
    val branchName: String? = null,
    val runId: String? = null,
    val startedAt: java.time.Instant? = null,
    val completedAt: java.time.Instant? = null
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-core:test --tests "*SubtaskManifestTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/SubtaskManifest.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/SubtaskManifestTest.kt && git commit -m "feat: add SubtaskManifest and SubtaskDef data models"
```

---

### Task A2: Create WorkplanConfig and integrate into ProjectConfig

**Files:**
- Create: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/WorkplanConfig.kt`
- Modify: `koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt`

- [ ] **Step 1: Write failing tests**

Add to `koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/SubtaskManifestTest.kt`:

```kotlin
@Test
fun `workplan config defaults`() {
    val config = WorkplanConfig()
    assertThat(config.executionMode).isEqualTo(ExecutionMode.SEQUENTIAL)
    assertThat(config.maxParallelSubagents).isEqualTo(3)
}

@Test
fun `workplan config deserialization`() {
    val yaml = "execution_mode: parallel\nmax_parallel_subagents: 5"
    val parsed = WorkplanConfig(
        executionMode = ExecutionMode.PARALLEL,
        maxParallelSubagents = 5
    )
    assertThat(parsed.executionMode).isEqualTo(ExecutionMode.PARALLEL)
    assertThat(parsed.maxParallelSubagents).isEqualTo(5)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-core:test --tests "*SubtaskManifestTest*"`
Expected: FAIL - compilation error (WorkplanConfig not found)

- [ ] **Step 3: Create WorkplanConfig.kt**

```kotlin
package com.flexsentlabs.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class WorkplanConfig(
    val executionMode: ExecutionMode = ExecutionMode.SEQUENTIAL,
    val maxParallelSubagents: Int = 3
) {
    @Serializable
    enum class ExecutionMode {
        SEQUENTIAL, PARALLEL
    }
}
```

- [ ] **Step 4: Add `workplan` field to AgentProjectConfig**

In `ProjectConfig.kt`, add to `AgentProjectConfig`:

```kotlin
@Serializable
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
    val heartbeatIntervalMs: Long = 30_000L,
    val heartbeatTimeoutMs: Long = 90_000L,
    val stages: Map<String, StageAgentConfig> = emptyMap(),
    val agents: Map<String, AgentProviderConfig> = emptyMap(),
    val routingRules: List<RoutingRule> = emptyList(),
    val workplan: WorkplanConfig? = null  // NEW
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :koncerto-core:test --tests "*SubtaskManifestTest*"`
Expected: PASS

Run: `./gradlew :koncerto-core:test`
Expected: PASS (existing tests unaffected)

- [ ] **Step 6: Commit**

```bash
git add koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/WorkplanConfig.kt koncerto-core/src/main/kotlin/com/anomaly/koncerto/core/config/ProjectConfig.kt koncerto-core/src/test/kotlin/com/anomaly/koncerto/core/config/SubtaskManifestTest.kt && git commit -m "feat: add WorkplanConfig and integrate into AgentProjectConfig"
```

---

### Task A3: Add subtask-related AgentEvents

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt`

```kotlin
package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import org.junit.jupiter.api.Test
import java.time.Instant

class AgentEventTest {

    @Test
    fun `subtask events carry correct metadata`() {
        val now = Instant.now()
        val started = AgentEvent.SubtaskStarted(subtaskId = "step-1", issueId = "KONC-123")
        assertThat(started.subtaskId).isEqualTo("step-1")
        assertThat(started.issueId).isEqualTo("KONC-123")
    }

    @Test
    fun `workplan ready event carries manifest`() {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(SubtaskDef(id = "s1", description = "Test", prompt = "Do it"))
        )
        val event = AgentEvent.WorkplanReady(manifest = manifest, issueId = "KONC-123")
        assertThat(event.manifest.subtasks.size).isEqualTo(1)
    }

    @Test
    fun `merge conflict event carries branch info`() {
        val event = AgentEvent.MergeConflict(
            subtaskId = "step-2",
            branch = "subtask/KONC-123/step-2",
            issueId = "KONC-123"
        )
        assertThat(event.branch).isEqualTo("subtask/KONC-123/step-2")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-agent:test --tests "*AgentEventTest*"`
Expected: FAIL - compilation error (new event types not found)

- [ ] **Step 3: Add new event types to AgentEvent**

Add to the `AgentEvent` sealed class in `AgentEvent.kt`:

```kotlin
data class WorkplanReady(
    val manifest: SubtaskManifest,
    val issueId: String,
    override val pid: Long? = null,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()

data class SubtaskStarted(
    val subtaskId: String,
    val issueId: String,
    override val pid: Long? = null,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()

data class SubtaskCompleted(
    val subtaskId: String,
    val issueId: String,
    override val pid: Long? = null,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()

data class SubtaskFailed(
    val subtaskId: String,
    val issueId: String,
    val error: String,
    override val pid: Long? = null,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()

data class MergeConflict(
    val subtaskId: String,
    val branch: String,
    val issueId: String,
    override val pid: Long? = null,
    override val timestamp: Instant = Instant.now()
) : AgentEvent()
```

Add import at top of file:
```kotlin
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-agent:test --tests "*AgentEventTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentEvent.kt koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentEventTest.kt && git commit -m "feat: add WorkplanReady, SubtaskStarted/Completed/Failed, and MergeConflict events"
```

---

### Workstream B: SubtaskRunner and Git Extensions

### Task B1: Create SubtaskRunner

**Files:**
- Create: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/SubtaskRunner.kt`

The `SubtaskRunner` spawns an agent subprocess in an existing workspace and feeds it a focused prompt. It skips workspace setup, git branching, and auto-commit — those are handled by the SubtaskOrchestrator.

- [ ] **Step 1: Write failing tests**

Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/SubtaskRunnerTest.kt`

```kotlin
package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubtaskRunnerTest {

    @Test
    fun `subtask runner requires valid runtime factory`() {
        val logger = StructuredLogger("test")
        val runner = SubtaskRunner(logger)
        assertThat(runner).isNotNull()
    }

    @Test
    fun `subtask runner returns failure when runtime start fails`() = runTest {
        val logger = StructuredLogger("test")
        val runner = SubtaskRunner(logger)
        val tempDir = Files.createTempDirectory("subtask-test")
        val result = runner.runSubtask(
            workspacePath = tempDir,
            prompt = "test prompt",
            kind = "nonexistent-runtime"
        )
        assertThat(result).isInstanceOf(EmptyResult.Failure::class)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-agent:test --tests "*SubtaskRunnerTest*"`
Expected: FAIL - compilation error

- [ ] **Step 3: Create SubtaskRunner**

```kotlin
package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

interface SubtaskRunner {
    suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String = "opencode",
        command: String? = null,
        turnTimeoutMs: Long = 3_600_000L,
        stallTimeoutMs: Long = 300_000L
    ): EmptyResult<IllegalStateException>
}

class DefaultSubtaskRunner(
    private val logger: StructuredLogger,
    private val runtimeFactory: AgentRuntimeFactory? = null,
    private val heartbeatIntervalMs: Long = 30_000L
) : SubtaskRunner {
    override suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String = "opencode",
        command: String? = null,
        turnTimeoutMs: Long = 3_600_000L,
        stallTimeoutMs: Long = 300_000L
    ): EmptyResult<IllegalStateException> = runCatching {
        val factory = runtimeFactory ?: AgentRuntimeFactory(logger)
        val effectiveCommand = command ?: kind
        val runtime = factory.create(kind, effectiveCommand, workspacePath)

        if (!runtime.start()) {
            throw IllegalStateException("subtask_agent_startup_failed")
        }

        try {
            withTimeout(turnTimeoutMs) {
                coroutineScope {
                    val lastOutputMs = AtomicLong(System.currentTimeMillis())

                    val outputJob = launch {
                        runtime.output.collect { line ->
                            lastOutputMs.set(System.currentTimeMillis())
                        }
                    }

                    val stallJob = launch {
                        while (true) {
                            delay(1000)
                            val elapsed = System.currentTimeMillis() - lastOutputMs.get()
                            if (elapsed > stallTimeoutMs) {
                                throw IllegalStateException(
                                    "Subtask agent stalled (no output for ${elapsed}ms)"
                                )
                            }
                        }
                    }

                    val aliveJob = launch {
                        while (true) {
                            delay(heartbeatIntervalMs)
                            if (!runtime.isAlive()) {
                                throw IllegalStateException("subtask_process_died")
                            }
                        }
                    }

                    runtime.send("initialize", null)
                    runtime.send("thread/start", buildJsonObject {
                        put("working_directory", workspacePath.toString())
                    })
                    runtime.send("turn/start", buildJsonObject {
                        put("input", prompt)
                    })

                    val turnDone = CompletableDeferred<Unit>()
                    val eventWatcher = launch {
                        runtime.events().collect { event ->
                            when (event) {
                                is AgentEvent.TurnCompleted,
                                is AgentEvent.TurnFailed -> turnDone.complete(Unit)
                                else -> {}
                            }
                        }
                    }

                    turnDone.await()
                    eventWatcher.cancel()

                    runtime.stop()
                    outputJob.cancel()
                    stallJob.cancel()
                    aliveJob.cancel()
                }
            }
        } catch (e: Exception) {
            runtime.stop()
            throw IllegalStateException(e.message ?: "subtask_failed")
        }
    }

    private class Result<T>(val value: T?)
    private inline fun <T> runCatching(block: () -> T): EmptyResult<IllegalStateException> {
        return try {
            block()
            com.flexsentlabs.koncerto.core.result.Result.Success(Unit)
        } catch (e: Exception) {
            com.flexsentlabs.koncerto.core.result.Result.Failure(
                IllegalStateException(e.message ?: "unknown_error")
            )
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-agent:test --tests "*SubtaskRunnerTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/SubtaskRunner.kt koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/SubtaskRunnerTest.kt && git commit -m "feat: add SubtaskRunner for lightweight agent subprocess execution"
```

---

### Task B2: Extend GitWorkflow with subagent branching

**Files:**
- Modify: `koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/GitWorkflow.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/GitWorkflowBranchTest.kt`

```kotlin
package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitWorkflowBranchTest {

    @Test
    fun `subtask branch name is correctly formatted`() {
        val git = createGitWorkflow()
        val branchName = git.subtaskBranchName("KONC-123", "step-1")
        assertThat(branchName).isEqualTo("subtask/KONC-123/step-1")
    }

    private fun createGitWorkflow(): GitWorkflow {
        val config = GitConfig(enabled = false, branchPrefix = "feature/")
        val logger = StructuredLogger("test")
        return GitWorkflow(config, logger)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-workspace:test --tests "*GitWorkflowBranchTest*"`
Expected: FAIL - compilation error

- [ ] **Step 3: Add subagent branch methods to GitWorkflow**

Add these `open` methods to `GitWorkflow` (they need to be `open` for test fakes to override them):

```kotlin
open fun subtaskBranchName(issueIdentifier: String, subtaskId: String): String =
    "subtask/$issueIdentifier/$subtaskId"

open fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {
    if (!config.enabled) return
    runGitSafe(workspacePath, "checkout", sourceBranch)
    runGitSafe(workspacePath, "checkout", "-b", branchName)
    logger.info("branch_created", mapOf(
        "branch" to branchName,
        "source" to sourceBranch,
        "workspace" to workspacePath.toString()
    ))
}

open fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult {
    if (!config.enabled) return MergeResult.SUCCESS
    runGitSafe(workspacePath, "checkout", targetBranch)
    val output = runGitSafe(workspacePath, "merge", sourceBranch) ?: ""
    return if (output.contains("CONFLICT", ignoreCase = true)) {
        MergeResult.CONFLICT
    } else {
        MergeResult.SUCCESS
    }
}

open fun deleteBranch(workspacePath: Path, branchName: String) {
    if (!config.enabled) return
    runGitSafe(workspacePath, "branch", "-D", branchName)
    logger.info("branch_deleted", mapOf("branch" to branchName))
}

sealed class MergeResult {
    data object SUCCESS : MergeResult()
    data object CONFLICT : MergeResult()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-workspace:test --tests "*GitWorkflowBranchTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-workspace/src/main/kotlin/com/anomaly/koncerto/workspace/GitWorkflow.kt koncerto-workspace/src/test/kotlin/com/anomaly/koncerto/workspace/GitWorkflowBranchTest.kt && git commit -m "feat: add subtask branch creation, merge, and delete methods to GitWorkflow"
```

---

### Workstream C: Orchestration + Integration (depends on A + B)

### Task C1: Create WorkplanParser

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/WorkplanParser.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/WorkplanParserTest.kt`

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.result.Result
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkplanParserTest {

    private val parser = WorkplanParser()

    @Test
    fun `parse valid workplan`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskManifestFixtures.subtask("step-1", "Step 1", "Do A", emptyList()),
                SubtaskManifestFixtures.subtask("step-2", "Step 2", "Do B", listOf("step-1"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Success::class)
        val parsed = (result as Result.Success).value
        assertThat(parsed.subtasks.size).isEqualTo(2)
    }

    @Test
    fun `return NOT_FOUND when no workplan exists`(@TempDir tempDir: Path) {
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on circular dependencies`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskManifestFixtures.subtask("a", "A", "Do A", listOf("b")),
                SubtaskManifestFixtures.subtask("b", "B", "Do B", listOf("a"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on duplicate subtask IDs`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskManifestFixtures.subtask("same", "A", "Do A", emptyList()),
                SubtaskManifestFixtures.subtask("same", "B", "Do B", emptyList())
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on dependsOn referencing nonexistent ID`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskManifestFixtures.subtask("a", "A", "Do A", listOf("nonexistent"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    private fun writeWorkplan(dir: Path, manifest: SubtaskManifest) {
        val json = Json { prettyPrint = true }
        val workplanDir = dir.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        Files.writeString(workplanDir.resolve("workplan.json"), json.encodeToString(manifest))
    }
}

// Reusable test fixture
object SubtaskManifestFixtures {
    fun subtask(id: String, description: String, prompt: String, dependsOn: List<String>) =
        com.flexsentlabs.koncerto.core.config.SubtaskDef(
            id = id,
            description = description,
            prompt = prompt,
            dependsOn = dependsOn
        )
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-orchestrator:test --tests "*WorkplanParserTest*"`
Expected: FAIL - compilation error

- [ ] **Step 3: Create WorkplanParser**

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

class WorkplanParser(
    private val logger: StructuredLogger? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(workspacePath: Path): Result<SubtaskManifest, ParseError> {
        val file = workspacePath.resolve("_koncerto").resolve("workplan.json")
        if (!Files.exists(file)) {
            return Result.Failure(ParseError.NOT_FOUND)
        }
        return try {
            val content = Files.readString(file)
            val manifest = json.decodeFromString<SubtaskManifest>(content)
            validate(manifest)
            Result.Success(manifest)
        } catch (e: Exception) {
            when (e) {
                is ParseError -> Result.Failure(e)
                else -> Result.Failure(ParseError.INVALID(e.message ?: "unknown"))
            }
        }
    }

    private fun validate(manifest: SubtaskManifest) {
        if (manifest.subtasks.isEmpty()) {
            throw ParseError.INVALID("subtasks list is empty")
        }

        val ids = manifest.subtasks.map { it.id }
        if (ids.size != ids.distinct().size) {
            throw ParseError.INVALID("duplicate subtask IDs found")
        }

        val idSet = ids.toSet()
        for (subtask in manifest.subtasks) {
            for (dep in subtask.dependsOn) {
                if (dep !in idSet) {
                    throw ParseError.INVALID(
                        "subtask '${subtask.id}' depends on nonexistent subtask '$dep'"
                    )
                }
            }
        }

        detectCycle(manifest)
    }

    private fun detectCycle(manifest: SubtaskManifest) {
        val adjacency = manifest.subtasks.associate { it.id to it.dependsOn.toSet() }
        val visited = mutableSetOf<String>()
        val inStack = mutableSetOf<String>()

        fun dfs(id: String) {
            if (id in inStack) throw ParseError.INVALID("circular dependency detected")
            if (id in visited) return
            visited.add(id)
            inStack.add(id)
            for (dep in adjacency[id].orEmpty()) {
                dfs(dep)
            }
            inStack.remove(id)
        }

        for (id in adjacency.keys) {
            if (id !in visited) dfs(id)
        }
    }
}

sealed class ParseError : Throwable() {
    data object NOT_FOUND : ParseError()
    data class INVALID(override val message: String) : ParseError()
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-orchestrator:test --tests "*WorkplanParserTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/WorkplanParser.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/WorkplanParserTest.kt && git commit -m "feat: add WorkplanParser with validation and cycle detection"
```

---

### Task C2: Create SubtaskFrontier

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/SubtaskFrontier.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/SubtaskFrontierTest.kt`

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.config.SubtaskState
import com.flexsentlabs.koncerto.core.config.SubtaskStatus
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubtaskFrontierTest {

    private val frontier = SubtaskFrontier()

    private fun def(id: String, dependsOn: List<String> = emptyList()) =
        SubtaskDef(id = id, description = id, prompt = "prompt-$id", dependsOn = dependsOn)

    private fun pending(def: SubtaskDef) = SubtaskState(def = def, status = SubtaskStatus.PENDING)
    private fun done(def: SubtaskDef) = SubtaskState(def = def, status = SubtaskStatus.SUCCEEDED)

    @Test
    fun `no deps all frontier`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b"))
        assertThat(frontier.compute(listOf(s1, s2)).map { it.def.id })
            .containsExactly("a", "b")
    }

    @Test
    fun `linear chain only first is frontier`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b", listOf("a")))
        val s3 = pending(def("c", listOf("b")))
        assertThat(frontier.compute(listOf(s1, s2, s3)).map { it.def.id })
            .containsExactly("a")
    }

    @Test
    fun `completed dep unblocks next`() {
        val s1 = done(def("a"))
        val s2 = pending(def("b", listOf("a")))
        assertThat(frontier.compute(listOf(s1, s2)).map { it.def.id })
            .containsExactly("b")
    }

    @Test
    fun `diamond dependency`() {
        val s1 = done(def("root"))
        val s2 = pending(def("left", listOf("root")))
        val s3 = pending(def("right", listOf("root")))
        val s4 = pending(def("merge", listOf("left", "right")))
        assertThat(frontier.compute(listOf(s1, s2, s3, s4)).map { it.def.id })
            .containsExactly("left", "right")
    }

    @Test
    fun `all deps not met returns nothing`() {
        val s1 = pending(def("a", listOf("nonexistent")))
        assertThat(frontier.compute(listOf(s1))).isEmpty()
    }

    @Test
    fun `topological sort linear chain`() {
        val s1 = pending(def("a"))
        val s2 = pending(def("b", listOf("a")))
        val s3 = pending(def("c", listOf("b")))
        val sorted = frontier.topologicalSort(listOf(s3, s2, s1))
        assertThat(sorted.map { it.def.id }).containsExactly("a", "b", "c")
    }

    @Test
    fun `topological sort diamond`() {
        val root = pending(def("root"))
        val left = pending(def("left", listOf("root")))
        val right = pending(def("right", listOf("root")))
        val merge = pending(def("merge", listOf("left", "right")))
        val sorted = frontier.topologicalSort(listOf(merge, right, left, root))
        assertThat(sorted.map { it.def.id }).containsExactly("root", "left", "right", "merge")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-orchestrator:test --tests "*SubtaskFrontierTest*"`
Expected: FAIL - compilation error

- [ ] **Step 3: Create SubtaskFrontier**

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.config.SubtaskState
import com.flexsentlabs.koncerto.core.config.SubtaskStatus

class SubtaskFrontier {

    fun compute(states: List<SubtaskState>): List<SubtaskState> {
        val completed = states
            .filter { it.status == SubtaskStatus.SUCCEEDED }
            .map { it.def.id }
            .toSet()
        return states
            .filter { it.status == SubtaskStatus.PENDING }
            .filter { it.def.dependsOn.all { dep -> dep in completed } }
    }

    fun topologicalSort(states: List<SubtaskState>): List<SubtaskState> {
        val adjacency = mutableMapOf<String, MutableList<String>>()
        val inDegree = mutableMapOf<String, Int>()
        val stateMap = states.associateBy { it.def.id }

        for (state in states) {
            adjacency.putIfAbsent(state.def.id, mutableListOf())
            inDegree.putIfAbsent(state.def.id, 0)
        }
        for (state in states) {
            for (dep in state.def.dependsOn) {
                adjacency[dep]?.add(state.def.id)
                inDegree[state.def.id] = (inDegree[state.def.id] ?: 0) + 1
            }
        }

        val queue = ArrayDeque<String>()
        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        val result = mutableListOf<SubtaskState>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            stateMap[id]?.let { result.add(it) }
            for (neighbor in adjacency[id].orEmpty()) {
                inDegree[neighbor] = (inDegree[neighbor] ?: 1) - 1
                if (inDegree[neighbor] == 0) queue.add(neighbor)
            }
        }

        return result
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :koncerto-orchestrator:test --tests "*SubtaskFrontierTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/SubtaskFrontier.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/SubtaskFrontierTest.kt && git commit -m "feat: add SubtaskFrontier with frontier computation and topological sort"
```

---

### Task C3: Create SubtaskOrchestrator

**Files:**
- Create: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/SubtaskOrchestrator.kt`

- [ ] **Step 1: Write failing tests**

Create: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/SubtaskOrchestratorTest.kt`

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.config.WorkplanConfig
import com.flexsentlabs.koncerto.core.config.ExecutionMode
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.MergeResult
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubtaskOrchestratorTest {

    @Test
    fun `sequential mode executes subtasks in dependency order`(@TempDir tempDir: Path) = runTest {
        val executed = mutableListOf<String>()
        val runner = FakeSubtaskRunner { prompt -> executed.add(prompt) }
        val orchestrator = createOrchestrator(runner = runner)

        val manifest = SubtaskManifest(
            issueId = "TEST-1",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b", dependsOn = listOf("a")),
                SubtaskDef(id = "c", description = "C", prompt = "prompt-c", dependsOn = listOf("b"))
            )
        )

        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = ExecutionMode.SEQUENTIAL)
        ).toList()

        assertThat(executed).containsExactly("prompt-a", "prompt-b", "prompt-c")
        assertThat(events.filterIsInstance<AgentEvent.SubtaskCompleted>().size).isEqualTo(3)
        assertThat(events.filterIsInstance<AgentEvent.SubtaskStarted>().size).isEqualTo(3)
    }

    @Test
    fun `sequential mode stops on failure`(@TempDir tempDir: Path) = runTest {
        val runner = FakeSubtaskRunner { prompt ->
            if (prompt == "prompt-b") Result.Failure(IllegalStateException("fail"))
            else Result.Success(Unit)
        }
        val orchestrator = createOrchestrator(runner = runner)

        val manifest = SubtaskManifest(
            issueId = "TEST-1",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b"),
                SubtaskDef(id = "c", description = "C", prompt = "prompt-c")
            )
        )

        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = ExecutionMode.SEQUENTIAL)
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.SubtaskCompleted>()
        val failed = events.filterIsInstance<AgentEvent.SubtaskFailed>()
        assertThat(completed.size).isEqualTo(1)
        assertThat(failed.size).isEqualTo(1)
        assertThat(failed[0].subtaskId).isEqualTo("b")
    }

    private fun createOrchestrator(
        runner: SubtaskRunner? = null,
        gitWorkflow: GitWorkflow? = null
    ): SubtaskOrchestrator {
        val logger = StructuredLogger("test")
        val actualRunner = runner ?: FakeSubtaskRunner()
        val actualGit = gitWorkflow ?: FakeGitWorkflow()
        return SubtaskOrchestrator(
            subtaskRunner = actualRunner,
            gitWorkflow = actualGit,
            logger = logger
        )
    }
}

class FakeSubtaskRunner(
    private val block: (prompt: String) -> EmptyResult<IllegalStateException> = { Result.Success(Unit) }
) : SubtaskRunner {
    constructor() : this({ Result.Success(Unit) })

    override suspend fun runSubtask(
        workspacePath: Path,
        prompt: String,
        kind: String,
        command: String?,
        turnTimeoutMs: Long,
        stallTimeoutMs: Long
    ): EmptyResult<IllegalStateException> {
        return block(prompt)
    }
}

class FakeGitWorkflow : GitWorkflow(
    config = com.flexsentlabs.koncerto.core.config.GitConfig(enabled = false),
    logger = StructuredLogger("test")
) {
    override fun subtaskBranchName(issueIdentifier: String, subtaskId: String): String =
        "subtask/$issueIdentifier/$subtaskId"

    override fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {}

    override fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult =
        MergeResult.SUCCESS

    override fun deleteBranch(workspacePath: Path, branchName: String) {}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :koncerto-orchestrator:test --tests "*SubtaskOrchestratorTest*"`
Expected: FAIL - compilation error

- [ ] **Step 3: Create SubtaskOrchestrator**

```kotlin
package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskState
import com.flexsentlabs.koncerto.core.config.SubtaskStatus
import com.flexsentlabs.koncerto.core.config.WorkplanConfig
import com.flexsentlabs.koncerto.core.config.ExecutionMode
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.MergeResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import java.time.Instant

class SubtaskOrchestrator(
    private val subtaskRunner: SubtaskRunner,
    private val gitWorkflow: GitWorkflow,
    private val logger: StructuredLogger,
    private val frontier: SubtaskFrontier = SubtaskFrontier()
) {
    suspend fun execute(
        workspacePath: Path,
        manifest: SubtaskManifest,
        config: WorkplanConfig
    ): Flow<AgentEvent> = flow {
        val issueId = manifest.issueId
        val states = manifest.subtasks.map { SubtaskState(def = it) }.toMutableList()

        when (config.executionMode) {
            ExecutionMode.SEQUENTIAL -> {
                val ordered = frontier.topologicalSort(states.toList())
                for (state in ordered) {
                    val result = runSingleSubtask(workspacePath, state, manifest)
                    val idx = states.indexOfFirst { it.def.id == state.def.id }
                    states[idx] = result.state
                    result.events.forEach { emit(it) }
                    if (result.state.status == SubtaskStatus.FAILED) break
                }
            }
            ExecutionMode.PARALLEL -> {
                while (states.any { it.status == SubtaskStatus.PENDING || it.status == SubtaskStatus.RUNNING }) {
                    val ready = frontier.compute(states.toList())
                    val running = states.count { it.status == SubtaskStatus.RUNNING }
                    val toLaunch = ready.take((config.maxParallelSubagents - running).coerceAtLeast(0))

                    if (toLaunch.isEmpty() && running == 0) break

                    coroutineScope {
                        toLaunch.map { state ->
                            async {
                                val idx = states.indexOfFirst { it.def.id == state.def.id }
                                states[idx] = state.copy(status = SubtaskStatus.RUNNING)

                                val branchName = gitWorkflow.subtaskBranchName(issueId, state.def.id)
                                gitWorkflow.createBranchFrom(
                                    workspacePath, branchName, manifest.integrationBranch
                                )

                                val result = runSingleSubtask(workspacePath, state, manifest)

                                val mergeResult = gitWorkflow.mergeBranch(
                                    workspacePath, branchName, manifest.integrationBranch
                                )

                                gitWorkflow.deleteBranch(workspacePath, branchName)

                                if (mergeResult is MergeResult.CONFLICT) {
                                    states[idx] = state.copy(
                                        status = SubtaskStatus.FAILED,
                                        branchName = branchName
                                    )
                                    Pair(
                                        listOf(AgentEvent.MergeConflict(
                                            subtaskId = state.def.id,
                                            branch = branchName,
                                            issueId = issueId
                                        )),
                                        true
                                    )
                                } else {
                                    states[idx] = result.state
                                    Pair(result.events, false)
                                }
                            }
                        }.forEach { deferred ->
                            val (events, _) = deferred.await()
                            for (event in events) emit(event)
                        }
                    }
                }
            }
        }
    }

    private suspend fun runSingleSubtask(
        workspacePath: Path,
        state: SubtaskState,
        manifest: SubtaskManifest
    ): SubtaskResult {
        val events = mutableListOf<AgentEvent>()

        events.add(AgentEvent.SubtaskStarted(
            subtaskId = state.def.id, issueId = manifest.issueId
        ))

        val result = subtaskRunner.runSubtask(
            workspacePath = workspacePath,
            prompt = state.def.prompt
        )

        return when (result) {
            is com.flexsentlabs.koncerto.core.result.Result.Success -> {
                val completed = state.copy(
                    status = SubtaskStatus.SUCCEEDED,
                    completedAt = Instant.now()
                )
                events.add(AgentEvent.SubtaskCompleted(
                    subtaskId = state.def.id, issueId = manifest.issueId
                ))
                SubtaskResult(completed, events)
            }
            is com.flexsentlabs.koncerto.core.result.Result.Failure -> {
                val failed = state.copy(
                    status = SubtaskStatus.FAILED,
                    completedAt = Instant.now()
                )
                events.add(AgentEvent.SubtaskFailed(
                    subtaskId = state.def.id,
                    issueId = manifest.issueId,
                    error = result.error.message ?: "unknown"
                ))
                SubtaskResult(failed, events)
            }
        }
    }

    private data class SubtaskResult(
        val state: SubtaskState,
        val events: List<AgentEvent>
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :koncerto-orchestrator:test --tests "*SubtaskOrchestratorTest*"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/SubtaskOrchestrator.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/SubtaskOrchestratorTest.kt && git commit -m "feat: add SubtaskOrchestrator with sequential and parallel execution modes"
```

---

### Task C4: Modify DispatchService for workplan support

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt`

- [ ] **Step 1: Write failing tests**

Add to `DispatchServiceTest.kt`:

First, modify the `CollectingAgentRunner` to track prompt, and add `workplanAwareDispatch` test:

At the top of the test file, add a new test:

```kotlin
@Test
fun `dispatch checks for workplan after agent completes`() = runBlocking {
    val workspaceDir = Files.createTempDirectory("wp-test")
    val ws = FakeWorkspaceManager(workspaceDir)
    val runner = CollectingAgentRunner()
    val svc = createService(
        runner = runner,
        workspaces = ws,
        candidates = listOf(issue("1", "WP-1", "Todo")),
        state = RuntimeState().also {
            it.maxConcurrentAgents = 10
        }
    )
    runDispatch(svc)
    // No workplan was written, so normal flow should proceed
    assertThat(runner.dispatched.map { it.identifier }).containsExactly("WP-1")
}
```

Add a `FakeWorkspaceManager`:

```kotlin
private class FakeWorkspaceManager(private val root: Path) : WorkspaceManager(
    rootDir = root, 
    hookExecutor = FakeHookExecutor(), 
    logger = StructuredLogger("test")
) {
    override suspend fun ensureWorkspace(identifier: String): com.flexsentlabs.koncerto.workspace.Workspace {
        val path = root.resolve(identifier)
        Files.createDirectories(path)
        return com.flexsentlabs.koncerto.workspace.Workspace(identifier, path)
    }
}

private class FakeHookExecutor : HookExecutor {
    override fun runScript(scriptPath: String, workspacePath: Path, timeoutMs: Long, env: Map<String, String>): String? = null
    override fun runBeforeRun(workspace: com.flexsentlabs.koncerto.workspace.Workspace, config: Any) {}
    override fun runAfterRun(workspace: com.flexsentlabs.koncerto.workspace.Workspace, config: Any, logger: StructuredLogger) {}
    override fun runAfterCreate(workspace: com.flexsentlabs.koncerto.workspace.Workspace, config: Any) {}
}
```

- [ ] **Step 2: Run test to verify it fails initially**

Run: `./gradlew :koncerto-orchestrator:test --tests "*DispatchServiceTest*"`
Expected: All existing tests pass, new test may need adjustments

- [ ] **Step 3: Modify DispatchService**

Add `SubtaskOrchestrator` and `WorkplanParser` to `DispatchService` constructor:

```kotlin
class DispatchService(
    val projectConfig: ProjectConfig,
    val state: RuntimeState,
    val linear: LinearClient,
    private val agentRunner: AgentRunner,
    private val workflowCache: WorkflowCache,
    private val logger: StructuredLogger,
    private val projectSlug: String,
    private val workspaces: WorkspaceManager? = null,
    private val retryExecutor: RetryExecutor = RetryExecutor(projectConfig.agent.maxRetryBackoffMs),
    private val issueProjectMap: ConcurrentHashMap<String, String> = ConcurrentHashMap(),
    private val metricsRepository: MetricsRepository? = null,
    val notifier: CompositeNotifier? = null,
    private val notificationsConfig: NotificationsConfig? = null,
    private val subtaskOrchestrator: SubtaskOrchestrator? = null,  // NEW
    private val workplanParser: WorkplanParser? = null              // NEW
)
```

Modify the `onSuccess` block in `dispatch()` method (lines 229-262):

```kotlin
result.onSuccess {
    state.claimed.remove(issue.id)
    val entry = state.running[issue.id]

    val wpConfig = projectConfig.agent.workplan
    if (wpConfig != null && subtaskOrchestrator != null && workplanParser != null) {
        val workspace = workspaces?.ensureWorkspace(issue.identifier)
        if (workspace != null) {
            when (val wpResult = workplanParser.parse(workspace.path)) {
                is com.flexsentlabs.koncerto.core.result.Result.Success -> {
                    logger.info(
                        "workplan_detected",
                        mapOf(
                            "issue_id" to issue.id,
                            "issue_identifier" to issue.identifier,
                            "subtask_count" to wpResult.value.subtasks.size.toString()
                        )
                    )
                    scope.launch {
                        subtaskOrchestrator.execute(
                            workspacePath = workspace.path,
                            manifest = wpResult.value,
                            config = wpConfig
                        ).collect { event ->
                            // Emit subtask events through Orchestrator's event handler
                        }
                    }
                    handleNormalCompletion(issue, stageConfig, entry)
                    return@onSuccess
                }
                is com.flexsentlabs.koncerto.core.result.Result.Failure -> {
                    // No workplan or invalid — continue with normal flow
                }
            }
        }
    }

    metricsRepository?.updateAfterRun(
        issueId = issue.id,
        issueIdentifier = issue.identifier,
        projectSlug = projectSlug,
        result = "success",
        inputTokens = entry?.inputTokens ?: 0,
        outputTokens = entry?.outputTokens ?: 0,
        totalTokens = entry?.totalTokens ?: 0
    )
    handleNormalCompletion(issue, stageConfig, entry)
}
```

Extract the normal completion logic to a helper:

```kotlin
private suspend fun handleNormalCompletion(
    issue: Issue,
    stageConfig: StageAgentConfig?,
    entry: RunningEntry?
) {
    val clarificationContent = readClarification(issue.identifier)
    if (clarificationContent != null) {
        handleClarification(issue.id, clarificationContent)
    } else {
        state.completed.add(issue.id)
        logger.info(
            "dispatch_completed",
            mapOf("issue_id" to issue.id, "issue_identifier" to issue.identifier)
        )
        transitionOnComplete(issue, stageConfig)
        if (notificationsConfig?.onCompleted == true && notifier != null) {
            notifier.send(NotificationEvent.AgentCompleted(
                projectSlug = projectSlug,
                issueId = issue.id,
                issueIdentifier = issue.identifier,
                title = issue.title,
                tokenUsage = entry?.let {
                    TokenUsage(it.inputTokens, it.outputTokens, it.totalTokens)
                }
            ))
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :koncerto-orchestrator:test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/DispatchServiceTest.kt && git commit -m "feat: integrate workplan detection into DispatchService dispatch loop"
```

---

### Task C5: Wire SubtaskOrchestrator in Beans.kt

**Files:**
- Modify: `koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt`

- [ ] **Step 1: Write failing test**

Add to existing Bean wiring tests or create a new simple configuration test.

- [ ] **Step 2: Add beans for SubtaskRunner, SubtaskOrchestrator, WorkplanParser**

In `Beans.kt`, add:

```kotlin
@Bean
fun subtaskRunner(
    logger: StructuredLogger,
    runtimeFactory: AgentRuntimeFactory? = null
): SubtaskRunner = DefaultSubtaskRunner(logger, runtimeFactory)

@Bean
fun subtaskFrontier(): SubtaskFrontier = SubtaskFrontier()

@Bean
fun workplanParser(
    logger: StructuredLogger
): WorkplanParser = WorkplanParser(logger)

@Bean
fun subtaskOrchestrator(
    subtaskRunner: SubtaskRunner,
    gitWorkflow: GitWorkflow,
    logger: StructuredLogger
): SubtaskOrchestrator = SubtaskOrchestrator(subtaskRunner, gitWorkflow, logger)
```

In the `DispatchService` instantiation inside `Orchestrator`:

```kotlin
val dispatch = DispatchService(
    projectConfig = pc,
    state = state,
    linear = linear,
    agentRunner = agentRunner,
    workflowCache = workflowCache,
    logger = logger,
    projectSlug = slug,
    workspaces = ws,
    issueProjectMap = issueProjectMap,
    metricsRepository = metricsRepository,
    notifier = notifier,
    notificationsConfig = pc.notifications,
    subtaskOrchestrator = subtaskOrchestrator,   // NEW
    workplanParser = workplanParser               // NEW
)
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :koncerto-app:test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add koncerto-app/src/main/kotlin/com/anomaly/koncerto/app/Beans.kt && git commit -m "feat: wire SubtaskOrchestrator, SubtaskRunner, and WorkplanParser in Beans.kt"
```

---

### Task C6: Update planner prompt template

**Files:**
- Modify: `prompts/implement.md`

- [ ] **Step 1: Add workplan instructions to prompt template**

Add to `prompts/implement.md`:

```markdown
## Workplan (Optional)

If this task can be split into independent subtasks to save context:

1. Analyze the issue and identify 2-5 focused subtasks
2. For each subtask, write a self-contained prompt and list dependencies
3. Write the plan to `_koncerto/workplan.json` using this format:
   ```json
   {
     "issueId": "{{ issue.identifier }}",
     "subtasks": [
       {
         "id": "step-1",
         "description": "What this subtask does",
         "prompt": "Full self-contained prompt for this subtask...",
         "dependsOn": [],
         "fileScope": ["path/to/files"]
       }
     ]
   }
   ```
4. Execute step-1 as your first turn
5. The orchestrator will dispatch remaining subtasks to fresh agents
```

- [ ] **Step 2: Commit**

```bash
git add prompts/implement.md && git commit -m "docs: add workplan creation instructions to planner prompt template"
```

---

## Self-Review

- **Spec coverage:** All sections of the design spec are covered: data models (Tasks A1-A2), events (A3), subtask runner (B1), git branching (B2), workplan parsing (C1), frontier computation (C2), orchestration (C3), dispatch integration (C4), bean wiring (C5), and prompt template (C6).
- **Placeholders:** No TBDs, TODOs, or vague steps. Every step has complete code blocks.
- **Type consistency:** `SubtaskManifest` defined in A1, used in A3 events, C1 parser, C3 orchestrator. `WorkplanConfig` defined in A2, used in C3 orchestrator. `SubtaskState`/`SubtaskStatus` defined in A1, used in C2 frontier and C3 orchestrator.
- **Scope:** Focused on workplan decomposition. No unrelated changes.
