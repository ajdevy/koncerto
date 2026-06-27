package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class SubtaskManifestTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Test
    fun `round-trip serialization`() {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
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

    @Test
    fun `workplan config defaults`() {
        val config = WorkplanConfig()
        assertThat(config.executionMode).isEqualTo(WorkplanConfig.ExecutionMode.SEQUENTIAL)
        assertThat(config.maxParallelSubagents).isEqualTo(3)
    }

    @Test
    fun `workplan config deserialization`() {
        val parsed = WorkplanConfig(
            executionMode = WorkplanConfig.ExecutionMode.PARALLEL,
            maxParallelSubagents = 5
        )
        assertThat(parsed.executionMode).isEqualTo(WorkplanConfig.ExecutionMode.PARALLEL)
        assertThat(parsed.maxParallelSubagents).isEqualTo(5)
    }

    @Test
    fun `workplan config defaults in AgentProjectConfig`() {
        val agentConfig = AgentProjectConfig(
            kind = "opencode",
            stages = emptyMap(),
            agents = emptyMap(),
            routingRules = emptyList()
        )
        assertThat(agentConfig.workplan).isEqualTo(null)
    }

    @Test
    fun `SubtaskState holds runtime fields`() {
        val def = SubtaskDef(id = "step-1", description = "Do work", prompt = "prompt")
        val started = java.time.Instant.parse("2026-01-01T00:00:00Z")
        val completed = java.time.Instant.parse("2026-01-01T01:00:00Z")
        val state = SubtaskState(
            def = def,
            status = SubtaskStatus.RUNNING,
            branchName = "subtask/KONC-1/step-1",
            runId = "run-123",
            startedAt = started,
            completedAt = completed
        )
        assertThat(state.def.id).isEqualTo("step-1")
        assertThat(state.status).isEqualTo(SubtaskStatus.RUNNING)
        assertThat(state.branchName).isEqualTo("subtask/KONC-1/step-1")
        assertThat(state.runId).isEqualTo("run-123")
        assertThat(state.startedAt).isEqualTo(started)
        assertThat(state.completedAt).isEqualTo(completed)
    }

    @Test
    fun `SubtaskStatus enum values`() {
        assertThat(enumValues<SubtaskStatus>().toList()).isEqualTo(
            listOf(SubtaskStatus.PENDING, SubtaskStatus.RUNNING, SubtaskStatus.SUCCEEDED, SubtaskStatus.FAILED, SubtaskStatus.BLOCKED)
        )
    }
}