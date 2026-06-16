package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import com.flexsentlabs.koncerto.agent.AgentEvent
import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.result.EmptyResult
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.orchestrator.Orchestrator
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Test

class CliRunnerTest {

    private fun createOrchestrator(): Orchestrator {
        val fakeRunner = object : AgentRunner {
            override fun events(): Flow<AgentEvent> = MutableSharedFlow()

            override suspend fun run(
                issue: Issue,
                attempt: Int?,
                prompt: String,
                agentKindOverride: String?,
                commandOverride: String?,
                modelOverride: String?,
                turnTimeoutMs: Long?,
                stallTimeoutMs: Long?
            ): EmptyResult<IllegalStateException> = Result.Success(Unit)
        }
        return Orchestrator(
            config = com.flexsentlabs.koncerto.core.config.ServiceConfig(),
            linearClientFactory = { throw UnsupportedOperationException() },
            workspaceManagerFactory = { throw UnsupportedOperationException() },
            agentRunner = fakeRunner,
            workflowCache = WorkflowCache(),
            logger = StructuredLogger(emptyList<LogSink>()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        )
    }

    @Test
    fun `help prints help text`() {
        val runner = CliRunner(createOrchestrator())
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out))
        try {
            runner.run("help")
            val output = out.toString()
            assertThat(output).contains("Koncerto CLI")
            assertThat(output).contains("status")
            assertThat(output).contains("agents")
            assertThat(output).contains("restart")
        } finally {
            System.setOut(System.out)
        }
    }

    @Test
    fun `status prints project info`() {
        val runner = CliRunner(createOrchestrator())
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out))
        try {
            runner.run("status")
            val output = out.toString()
            assertThat(output).contains("[koncerto]")
            assertThat(output).contains("Projects: 0")
            assertThat(output).contains("Running: 0")
            assertThat(output).contains("Blocked: 0")
            assertThat(output).contains("Retrying: 0")
        } finally {
            System.setOut(System.out)
        }
    }

    @Test
    fun `agents prints agent info`() {
        val runner = CliRunner(createOrchestrator())
        val out = ByteArrayOutputStream()
        System.setOut(PrintStream(out))
        try {
            runner.run("agents")
            assertThat(out.toString()).isEqualTo("")
        } finally {
            System.setOut(System.out)
        }
    }

    @Test
    fun `restart calls orchestrator restart`() {
        val orchestrator = createOrchestrator()
        val runner = CliRunner(orchestrator)
        orchestrator.shutdownRequested = true
        runner.run("restart")
        assertThat(orchestrator.shutdownRequested).isFalse()
    }

    @Test
    fun `no args calls orchestrator start`() {
        val runner = CliRunner(createOrchestrator())
        runner.run()
    }

    @Test
    fun `unknown args calls orchestrator start`() {
        val runner = CliRunner(createOrchestrator())
        runner.run("nonexistent")
    }
}
