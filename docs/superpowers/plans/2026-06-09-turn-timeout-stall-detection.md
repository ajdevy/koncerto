# Turn Timeout and Stall Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce `turn_timeout_ms` and `stall_timeout_ms` from agent config — kill hanging agents, commit partial work, retry via existing backoff.

**Architecture:** Timeout enforcement lives in `DefaultAgentRunner.run()`. `DispatchService` passes per-project timeout values as new parameters. Stall detection monitors `runtime.output` flow; turn timeout wraps the entire agent interaction in `withTimeout`. On timeout: kill subprocess, commit+push partial work if any, return failure.

**Tech Stack:** Kotlin coroutines (`withTimeout`, `TimeoutCancellationException`), `AtomicLong`, `GitWorkflow.commitAndPush()`

---

## Files

- Modify: `koncerto-agent/.../AgentRunner.kt` — `AgentRunner` interface + `DefaultAgentRunner`
- Modify: `koncerto-orchestrator/.../DispatchService.kt` — pass timeout values to `run()`
- Modify: `koncerto-orchestrator/src/test/.../OrchestratorTest.kt` — update `FakeAgentRunner`, `FailingAgentRunner`
- Modify: `koncerto-agent/src/test/.../AgentRunnerTest.kt` — add timeout/stall tests
- Modify: `koncerto-dashboard/src/test/.../ApiV1ControllerTest.kt` — update `FakeAgentRunner`
- Modify: `koncerto-app/src/test/.../DashboardUiTestConfig.kt` — update `FakeAgentRunner`
- Create (if not exists): `koncerto-agent/src/test/.../AgentRunnerTest.kt` — timeout/stall unit tests

---

### Task 1: Update AgentRunner interface

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt:31-40`

- [ ] **Step 1: Add `turnTimeoutMs` and `stallTimeoutMs` to the `run()` interface**

```kotlin
interface AgentRunner {
    fun events(): Flow<AgentEvent>
    suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String? = null,
        commandOverride: String? = null,
        turnTimeoutMs: Long? = null,
        stallTimeoutMs: Long? = null
    ): EmptyResult<IllegalStateException>
}
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :koncerto-agent:compileKotlin`
Expected: BUILDS SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt
git commit -m "feat: add turnTimeoutMs and stallTimeoutMs params to AgentRunner interface"
```

---

### Task 2: Update all FakeAgentRunner implementations

**Files:**
- Modify: `koncerto-orchestrator/src/test/kotlin/com/anomaly/koncerto/orchestrator/OrchestratorTest.kt` — `FakeAgentRunner.run()` and `FailingAgentRunner.run()` signature
- Modify: `koncerto-dashboard/src/test/kotlin/com/anomaly/koncerto/dashboard/ApiV1ControllerTest.kt` — same
- Modify: `koncerto-app/src/test/kotlin/com/anomaly/koncerto/app/DashboardUiTestConfig.kt` — same
- Check: `koncerto-agent/src/test/.../AgentRunnerTest.kt` — for any existing FakeAgentRunner

- [ ] **Step 1: Update OrchestratorTest.kt Fakes**

In `OrchestratorTest.kt`, add `turnTimeoutMs: Long? = null, stallTimeoutMs: Long? = null` parameters to both `FakeAgentRunner.run()` and `FailingAgentRunner.run()`:

```kotlin
class FakeAgentRunner : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Success(Unit)
    }
}

class FailingAgentRunner(private val errorMsg: String) : AgentRunner {
    val dispatched = mutableListOf<Issue>()
    private val flow = MutableSharedFlow<AgentEvent>()
    override fun events() = flow.asSharedFlow()
    override suspend fun run(
        issue: Issue,
        attempt: Int?,
        prompt: String,
        agentKindOverride: String?,
        commandOverride: String?,
        turnTimeoutMs: Long?,
        stallTimeoutMs: Long?
    ): EmptyResult<IllegalStateException> {
        dispatched += issue
        return Result.Failure(IllegalStateException(errorMsg))
    }
}
```

- [ ] **Step 2: Find and update all other FakeAgentRunner implementations**

Search for `FakeAgentRunner` and `override suspend fun run` across the codebase. Update each to include the two new nullable parameters.

Run: `grep -rn "override suspend fun run" --include="*.kt"` to find all implementations.

Update each one to include `turnTimeoutMs: Long? = null, stallTimeoutMs: Long? = null`.

- [ ] **Step 3: Compile to verify all implementations are updated**

Run: `./gradlew compileTestKotlin`
Expected: BUILDS SUCCESSFUL

- [ ] **Step 4: Run existing tests to confirm no regressions**

Run: `./gradlew test -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: update fake runners for new AgentRunner.run() signature"
```

---

### Task 3: Implement timeout enforcement in DefaultAgentRunner

**Files:**
- Modify: `koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt` — `DefaultAgentRunner.run()`

- [ ] **Step 1: Add imports**

Add to existing imports:
```kotlin
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong
```

- [ ] **Step 2: Replace the `run()` method body**

Replace the current `run()` implementation (lines 55-123) with:

```kotlin
override suspend fun run(
    issue: Issue,
    attempt: Int?,
    prompt: String,
    agentKindOverride: String?,
    commandOverride: String?,
    turnTimeoutMs: Long?,
    stallTimeoutMs: Long?
): EmptyResult<IllegalStateException> = runCatchingResult {
    val workspace = workspaces.ensureWorkspace(issue.identifier)
    workspaces.assertInsideRoot(workspace.path)
    config.hooks.afterCreate?.let { workspaces.runAfterCreate(workspace, it) }
    config.hooks.beforeRun?.let { workspaces.runBeforeRun(workspace, it) }

    gitWorkflow?.createBranch(workspace.path, issue.identifier)

    val factory = runtimeFactory ?: AgentRuntimeFactory(logger)
    val effectiveKind = agentKindOverride ?: "opencode"
    val command = commandOverride ?: effectiveKind
    val runtime = factory.create(effectiveKind, command, workspace.path)
    if (!runtime.start()) throw IllegalStateException("startup_failed")

    val effectiveTurnTimeout = turnTimeoutMs ?: 3_600_000L
    val effectiveStallTimeout = stallTimeoutMs ?: 300_000L

    try {
        withTimeout(effectiveTurnTimeout) {
            coroutineScope {
                val lastOutputMs = AtomicLong(System.currentTimeMillis())

                val collectors = listOf(
                    launch {
                        runtime.output.collect { line ->
                            lastOutputMs.set(System.currentTimeMillis())
                            if (onAgentOutput != null) {
                                onAgentOutput(issue.id, line)
                            }
                        }
                    },
                    launch {
                        while (true) {
                            delay(1000)
                            val elapsed = System.currentTimeMillis() - lastOutputMs.get()
                            if (elapsed > effectiveStallTimeout) {
                                throw TimeoutCancellationException(
                                    "Agent stalled (no output for ${elapsed}ms)"
                                )
                            }
                        }
                    }
                )

                val rendered = PromptRenderer.render(
                    prompt, mapOf(
                        "issue" to issue.toTemplateMap(),
                        "attempt" to attempt
                    )
                )

                runtime.send("initialize", null)
                runtime.send(
                    "thread/start", buildJsonObject {
                        put("working_directory", workspace.path.toString())
                    }
                )
                runtime.send(
                    "turn/start", buildJsonObject {
                        put("input", rendered)
                    }
                )

                runtime.stop()
                collectors.forEach { it.cancel() }
            }
        }
    } catch (e: TimeoutCancellationException) {
        runtime.stop()
        gitWorkflow?.let { gw ->
            try {
                gw.commitAndPush(workspace.path, issue.identifier, issue.title, issue.labels)
                logger.info("partial_work_committed", mapOf(
                    "issue" to issue.identifier,
                    "reason" to (e.message ?: "timeout")
                ))
            } catch (_: Exception) {
                logger.warn("partial_commit_failed", mapOf("issue" to issue.identifier))
            }
        }
        throw IllegalStateException(e.message ?: "agent_timeout")
    }
    config.hooks.afterRun?.let { workspaces.runAfterRun(workspace, it, logger) }

    val clarificationPath = workspace.path.resolve(".koncerto").resolve("clarification.md")
    val clarificationRequested = Files.exists(clarificationPath)
    if (clarificationRequested) {
        logger.info("clarification_requested", mapOf("issue" to issue.identifier, "path" to clarificationPath.toString()))
    }

    if (!clarificationRequested) {
        gitWorkflow?.let { gw ->
            gw.commitAndPush(workspace.path, issue.identifier, issue.title, issue.labels)
            val prUrl = gw.createPullRequest(workspace.path, issue.identifier, issue.title, issue.description)
            if (prUrl != null) {
                logger.info("pr_created", mapOf("url" to prUrl, "issue" to issue.identifier))
            }
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :koncerto-agent:compileKotlin`
Expected: BUILDS SUCCESSFUL

- [ ] **Step 4: Run existing tests**

Run: `./gradlew test -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL (existing tests still pass, just no timeout-specific tests yet)

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/main/kotlin/com/anomaly/koncerto/agent/AgentRunner.kt
git commit -m "feat: enforce turn timeout and stall detection in DefaultAgentRunner"
```

---

### Task 4: Pass timeout values from DispatchService

**Files:**
- Modify: `koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt:144-145`

- [ ] **Step 1: Update the `agentRunner.run()` call in `dispatch()`**

Change line 145 from:
```kotlin
val result = agentRunner.run(issue, attempt, prompt, resolved.kind, resolved.command)
```
To:
```kotlin
val result = agentRunner.run(
    issue, attempt, prompt, resolved.kind, resolved.command,
    turnTimeoutMs = projectConfig.agent.turnTimeoutMs,
    stallTimeoutMs = projectConfig.agent.stallTimeoutMs
)
```

- [ ] **Step 2: Compile and test**

Run: `./gradlew :koncerto-orchestrator:compileKotlin :koncerto-orchestrator:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add koncerto-orchestrator/src/main/kotlin/com/anomaly/koncerto/orchestrator/DispatchService.kt
git commit -m "feat: pass turn timeout and stall timeout from DispatchService to AgentRunner"
```

---

### Task 5: Write timeout/stall tests for DefaultAgentRunner

**Files:**
- Create: `koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTimeoutTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.GitConfig
import com.flexsentlabs.koncerto.core.config.HooksConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.HookExecutor
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AgentRunnerTimeoutTest {

    private fun sampleConfig() = ServiceConfig(
        projects = mapOf("test" to ProjectConfig(
            tracker = TrackerConfig("linear", "x", "k", "p"),
            workspace = WorkspaceConfig("/tmp"),
            agent = AgentProjectConfig(
                kind = "opencode", command = "opencode",
                maxConcurrentAgents = 1, maxTurns = 5, maxRetryBackoffMs = 300000,
                turnTimeoutMs = 100, stallTimeoutMs = 50
            )
        )),
        hooks = HooksConfig(null, null, null, null, 60000),
        gitConfig = GitConfig()
    )

    @Test
    fun `run times out when turn exceeds turnTimeoutMs`() = runBlocking {
        val root = Files.createTempDirectory("ar-timeout-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(
            config = sampleConfig(),
            workspaces = mgr,
            logger = StructuredLogger(emptyList()),
            runtimeFactory = AgentRuntimeFactory { _, _, _ ->
                HangRuntime()
            }
        )
        val issue = Issue("1", "A-1", "test", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null)
        val result = runner.run(issue, 1, "do something", "opencode", null, turnTimeoutMs = 50, stallTimeoutMs = 50000)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `run detects stall when no output for stallTimeoutMs`() = runBlocking {
        val root = Files.createTempDirectory("ar-stall-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(
            config = sampleConfig(),
            workspaces = mgr,
            logger = StructuredLogger(emptyList()),
            runtimeFactory = AgentRuntimeFactory { _, _, _ ->
                StallRuntime()
            }
        )
        val issue = Issue("1", "A-1", "test", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null)
        val result = runner.run(issue, 1, "do something", "opencode", null, turnTimeoutMs = 50000, stallTimeoutMs = 50)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `run succeeds when turn completes before timeout`() = runBlocking {
        val root = Files.createTempDirectory("ar-ok-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(
            config = sampleConfig(),
            workspaces = mgr,
            logger = StructuredLogger(emptyList()),
            runtimeFactory = AgentRuntimeFactory { _, _, _ ->
                FastRuntime()
            }
        )
        val issue = Issue("1", "A-1", "test", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null)
        val result = runner.run(issue, 1, "do something", "opencode", null, turnTimeoutMs = 50000, stallTimeoutMs = 50000)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `stall timeout fires before turn timeout when stall is shorter`() = runBlocking {
        val root = Files.createTempDirectory("ar-stall-first-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val runner = DefaultAgentRunner(
            config = sampleConfig(),
            workspaces = mgr,
            logger = StructuredLogger(emptyList()),
            runtimeFactory = AgentRuntimeFactory { _, _, _ ->
                StallRuntime()
            }
        )
        val issue = Issue("1", "A-1", "test", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null)
        val result = runner.run(issue, 1, "do something", "opencode", null, turnTimeoutMs = 50000, stallTimeoutMs = 10)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `run commits partial work on timeout`() = runBlocking {
        val root = Files.createTempDirectory("ar-commit-")
        val mgr = WorkspaceManager(root, HookExecutor { _, _ -> })
        val commitLog = mutableListOf<String>()
        val gitWorkflow = object : GitWorkflow(GitConfig(enabled = true), StructuredLogger(emptyList())) {
            override fun commitAndPush(workspace: Path, identifier: String, title: String, labels: List<String>?) {
                commitLog.add(identifier)
            }
        }
        val runner = DefaultAgentRunner(
            config = sampleConfig(),
            workspaces = mgr,
            logger = StructuredLogger(emptyList()),
            runtimeFactory = AgentRuntimeFactory { _, _, _ ->
                HangRuntime()
            },
            gitWorkflow = gitWorkflow
        )
        val issue = Issue("1", "A-1", "test", null, 5, "Todo", null, null, emptyList(), emptyList(), null, null)
        runner.run(issue, 1, "do something", "opencode", null, turnTimeoutMs = 50, stallTimeoutMs = 50000)
        assertThat(commitLog).isNotEmpty()
    }
}

/** Runtime that never produces output or completes */
class HangRuntime : AgentRuntime {
    override val output: Flow<String> = flow { }
    override fun start(): Boolean = true
    override fun stop() {}
    override fun send(method: String, params: Any?) {}
    override val isRunning: Boolean get() = true
}

/** Runtime that produces one line then hangs */
class StallRuntime : AgentRuntime {
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 1)
    init { _output.tryEmit("started") }
    override val output: Flow<String> = _output.asSharedFlow()
    override fun start(): Boolean = true
    override fun stop() {}
    override fun send(method: String, params: Any?) {}
    override val isRunning: Boolean get() = true
}

/** Runtime that completes immediately */
class FastRuntime : AgentRuntime {
    override val output: Flow<String> = flow { }
    override fun start(): Boolean = true
    override fun stop() {}
    override fun send(method: String, params: Any?) {}
    override val isRunning: Boolean get() = true
}
```

- [ ] **Step 2: Check that `isFailure` and `isSuccess` are available in assertk or use manual check**

If assertk doesn't have `isFailure`/`isSuccess`, use `result.exceptionOrNull() != null` and `result.exceptionOrNull() == null`:

```kotlin
assertThat(result.exceptionOrNull()).isNotNull()    // for isFailure assertions
assertThat(result.exceptionOrNull()).isNull()       // for isSuccess assertions
```

Replace accordingly.

- [ ] **Step 3: Run the new tests**

Run: `./gradlew :koncerto-agent:test --tests "*AgentRunnerTimeoutTest"`
Expected: All tests pass

- [ ] **Step 4: Run all tests to check for regressions**

Run: `./gradlew test -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add koncerto-agent/src/test/kotlin/com/anomaly/koncerto/agent/AgentRunnerTimeoutTest.kt
git commit -m "test: timeout and stall detection tests for DefaultAgentRunner"
```

---

### Task 6: Final verification and push

- [ ] **Step 1: Run full test suite**

Run: `./gradlew test -x :koncerto-e2e:test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run JaCoCo coverage**

Run: `./gradlew jacocoTestReport -Pjacoco`
Expected: BUILD SUCCESSFUL (reports generated for all modules, new code covered)

- [ ] **Step 3: Push to origin**

```bash
git push origin main
```
