package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.SubtaskRunner
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.config.WorkplanConfig
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workspace.GitWorkflow
import com.flexsentlabs.koncerto.workspace.MergeResult
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SubtaskOrchestratorTest {

    @Test
    fun `sequential mode executes subtasks in dependency order`(@TempDir tempDir: Path) = runTest {
        val executed = mutableListOf<String>()
        val runner = FakeSubtaskRunner { prompt ->
            executed.add(prompt)
            Result.Success(Unit)
        }
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
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
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
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.SEQUENTIAL)
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.SubtaskCompleted>()
        val failed = events.filterIsInstance<AgentEvent.SubtaskFailed>()
        assertThat(completed.size).isEqualTo(1)
        assertThat(failed.size).isEqualTo(1)
        assertThat(failed[0].subtaskId).isEqualTo("b")
    }

    @Test
    fun `parallel mode deletes branch even when subtask runner throws`(@TempDir tempDir: Path) = runTest {
        val deletedBranches = mutableListOf<String>()
        val runner = FakeSubtaskRunner { throw RuntimeException("crash") }
        val git = object : FakeGitWorkflow() {
            override fun deleteBranch(workspacePath: Path, branchName: String) {
                deletedBranches.add(branchName)
            }
        }
        val orchestrator = createOrchestrator(runner = runner, gitWorkflow = git)
        val manifest = SubtaskManifest(
            issueId = "TEST-2",
            subtasks = listOf(SubtaskDef(id = "x", description = "X", prompt = "p"))
        )
        runCatching {
            orchestrator.execute(
                workspacePath = tempDir,
                manifest = manifest,
                config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.PARALLEL)
            ).toList()
        }
        assertThat(deletedBranches.isNotEmpty()).isTrue()
    }

    @Test
    fun `parallel mode executes independent subtasks successfully`(@TempDir tempDir: Path) = runTest {
        val executed = mutableListOf<String>()
        val runner = FakeSubtaskRunner { prompt ->
            executed.add(prompt)
            Result.Success(Unit)
        }
        val orchestrator = createOrchestrator(runner = runner)
        val manifest = SubtaskManifest(
            issueId = "TEST-P1",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b")
            )
        )
        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.PARALLEL)
        ).toList()
        assertThat(executed).containsExactlyInAnyOrder("prompt-a", "prompt-b")
        assertThat(events.filterIsInstance<AgentEvent.SubtaskCompleted>().size).isEqualTo(2)
        assertThat(events.filterIsInstance<AgentEvent.MergeConflict>().size).isEqualTo(0)
    }

    @Test
    fun `parallel mode emits merge conflict event`(@TempDir tempDir: Path) = runTest {
        val git = object : FakeGitWorkflow() {
            override fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult =
                MergeResult.CONFLICT
        }
        val orchestrator = createOrchestrator(gitWorkflow = git)
        val manifest = SubtaskManifest(
            issueId = "TEST-P2",
            subtasks = listOf(SubtaskDef(id = "x", description = "X", prompt = "prompt-x"))
        )
        val events = orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.PARALLEL)
        ).toList()
        val conflicts = events.filterIsInstance<AgentEvent.MergeConflict>()
        assertThat(conflicts.size).isEqualTo(1)
        assertThat(conflicts[0].subtaskId).isEqualTo("x")
        assertThat(conflicts[0].issueId).isEqualTo("TEST-P2")
    }

    @Test
    fun `parallel mode respects maxParallelSubagents semaphore`(@TempDir tempDir: Path) = runTest {
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val runner = object : SubtaskRunner {
            override suspend fun runSubtask(
                workspacePath: Path,
                prompt: String,
                kind: String,
                command: String?,
                turnTimeoutMs: Long,
                stallTimeoutMs: Long
            ): EmptyResult<IllegalStateException> {
                val current = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { existing -> maxOf(existing, current) }
                delay(50)
                concurrent.decrementAndGet()
                return Result.Success(Unit)
            }
        }
        val orchestrator = createOrchestrator(runner = runner)
        val manifest = SubtaskManifest(
            issueId = "TEST-P3",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "prompt-a"),
                SubtaskDef(id = "b", description = "B", prompt = "prompt-b"),
                SubtaskDef(id = "c", description = "C", prompt = "prompt-c"),
                SubtaskDef(id = "d", description = "D", prompt = "prompt-d")
            )
        )
        orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(
                executionMode = WorkplanConfig.ExecutionMode.PARALLEL,
                maxParallelSubagents = 2
            )
        ).toList()
        assertThat(maxConcurrent.get()).isLessThanOrEqualTo(2)
        assertThat(maxConcurrent.get()).isEqualTo(2)
    }

    @Test
    fun `parallel mode uses provided baseBranch instead of hardcoded main`(@TempDir tempDir: Path) = runTest {
        val createdFrom = mutableListOf<String>()
        val git = object : FakeGitWorkflow() {
            override fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {
                createdFrom.add(sourceBranch)
            }
        }
        val orchestrator = createOrchestrator(gitWorkflow = git)
        val manifest = SubtaskManifest(
            issueId = "TEST-3",
            subtasks = listOf(SubtaskDef(id = "y", description = "Y", prompt = "p"))
        )
        orchestrator.execute(
            workspacePath = tempDir,
            manifest = manifest,
            config = WorkplanConfig(executionMode = WorkplanConfig.ExecutionMode.PARALLEL),
            baseBranch = "develop"
        ).toList()
        assertThat(createdFrom.all { it == "develop" }).isTrue()
    }

    private fun createOrchestrator(
        runner: SubtaskRunner? = null,
        gitWorkflow: GitWorkflow? = null
    ): SubtaskOrchestrator {
        val logger = StructuredLogger(emptyList<LogSink>())
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

open class FakeGitWorkflow(
    config: com.flexsentlabs.koncerto.core.config.GitConfig = com.flexsentlabs.koncerto.core.config.GitConfig(enabled = false, branchPrefix = "feature/"),
    logger: StructuredLogger = StructuredLogger(emptyList<LogSink>())
) : GitWorkflow(config, logger) {
    override fun subtaskBranchName(issueIdentifier: String, subtaskId: String): String =
        "subtask/$issueIdentifier/$subtaskId"

    override fun createBranchFrom(workspacePath: Path, branchName: String, sourceBranch: String) {}

    override fun mergeBranch(workspacePath: Path, sourceBranch: String, targetBranch: String): MergeResult =
        MergeResult.SUCCESS

    override fun deleteBranch(workspacePath: Path, branchName: String) {}
}