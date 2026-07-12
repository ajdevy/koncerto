package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.File
import org.junit.jupiter.api.Test

class ScenarioCoverageClassifierTest {

    private fun noopLogger() = StructuredLogger(emptyList())

    private fun issue(title: String = "Register with email code", description: String? = "d") = Issue(
        id = "i", identifier = "T-1", title = title, description = description,
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private fun runnerReturning(vararg outputs: String?): DemoScenarioGenerator.ProcessRunner {
        val queue = ArrayDeque(outputs.toList())
        return DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long -> queue.removeFirstOrNull() }
    }

    private fun classifier(runner: DemoScenarioGenerator.ProcessRunner, models: List<String> = listOf("m1")) =
        ScenarioCoverageClassifier("opencode", noopLogger(), runner, models)

    @Test
    fun `parses an un-automatable scenario`() {
        val out = """[{"scenario":"log in via emailed code","why":"needs reading the inbox"}]"""
        val result = classifier(runnerReturning(out)).classify(issue())
        assertThat(result.map { it.scenario }).containsExactly("log in via emailed code")
        assertThat(result[0].why).isEqualTo("needs reading the inbox")
    }

    @Test
    fun `empty array yields no unverifiable scenarios`() {
        val result = classifier(runnerReturning("[]")).classify(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `malformed reply yields empty`() {
        val result = classifier(runnerReturning("nope")).classify(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `non-zero exit (null output) yields empty`() {
        val result = classifier(runnerReturning(null)).classify(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `swallows a runner exception and yields empty`() {
        val throwing = DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long ->
            throw RuntimeException("opencode blew up")
        }
        val result = classifier(throwing).classify(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `uses the default free-model list when none is supplied`() {
        val c = ScenarioCoverageClassifier("opencode", noopLogger(), runnerReturning("[]"))
        assertThat(c.classify(issue())).isEmpty()
    }

    @Test
    fun `skips array items whose scenario is blank`() {
        val out = """[{"scenario":"","why":"empty"},{"scenario":"log in via emailed code","why":"ok"}]"""
        val result = classifier(runnerReturning(out)).classify(issue())
        assertThat(result.map { it.scenario }).containsExactly("log in via emailed code")
    }

    @Test
    fun `balanced brackets with invalid JSON inside yield empty`() {
        val result = classifier(runnerReturning("[oops not json]")).classify(issue())
        assertThat(result).isEmpty()
    }
}
