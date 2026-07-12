package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.File
import org.junit.jupiter.api.Test

class TestResourceRequirementDetectorTest {

    private fun noopLogger() = StructuredLogger(emptyList())

    private fun issue(title: String = "Register with email code", description: String? = "d") = Issue(
        id = "i", identifier = "T-1", title = title, description = description,
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    // Records the outputs a fake model returns, one per model in order.
    private fun runnerReturning(vararg outputs: String?): DemoScenarioGenerator.ProcessRunner {
        val queue = ArrayDeque(outputs.toList())
        return DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long -> queue.removeFirstOrNull() }
    }

    private fun detector(runner: DemoScenarioGenerator.ProcessRunner, models: List<String> = listOf("m1")) =
        TestResourceRequirementDetector("opencode", noopLogger(), runner, models)

    @Test
    fun `parses a two-item resource list`() {
        val out = """[{"name":"test email inbox","why":"read the code"},{"name":"TEST_STRIPE_ACCOUNT","why":"charge"}]"""
        val result = detector(runnerReturning(out)).detect(issue())
        assertThat(result.map { it.name }).containsExactly("test email inbox", "TEST_STRIPE_ACCOUNT")
        assertThat(result[0].why).isEqualTo("read the code")
    }

    @Test
    fun `tolerates prose and markdown around the JSON array`() {
        val out = "Sure — here is the list:\n```json\n[{\"name\":\"test email inbox\"}]\n```\nDone."
        val result = detector(runnerReturning(out)).detect(issue())
        assertThat(result.map { it.name }).containsExactly("test email inbox")
    }

    @Test
    fun `empty array yields no resources`() {
        val result = detector(runnerReturning("[]")).detect(issue(description = "internal refactor"))
        assertThat(result).isEmpty()
    }

    @Test
    fun `malformed reply yields no resources`() {
        val result = detector(runnerReturning("not json at all")).detect(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `non-zero exit (null output) yields no resources`() {
        val result = detector(runnerReturning(null)).detect(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `cycles to the next model when the first reply is unparseable`() {
        val runner = runnerReturning("garbage", """[{"name":"test email inbox"}]""")
        val result = detector(runner, models = listOf("m1", "m2")).detect(issue())
        assertThat(result.map { it.name }).containsExactly("test email inbox")
    }

    @Test
    fun `swallows a runner exception and yields no resources`() {
        val throwing = DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long ->
            throw RuntimeException("opencode blew up")
        }
        val result = detector(throwing).detect(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `uses the default free-model list when none is supplied`() {
        // Constructing without an explicit models list must exercise the default-argument path.
        val d = TestResourceRequirementDetector("opencode", noopLogger(), runnerReturning("[]"))
        assertThat(d.detect(issue(description = "internal refactor"))).isEmpty()
    }

    @Test
    fun `skips array items whose name is blank`() {
        val out = """[{"name":"","why":"empty"},{"name":"test email inbox","why":"ok"}]"""
        val result = detector(runnerReturning(out)).detect(issue())
        assertThat(result.map { it.name }).containsExactly("test email inbox")
    }

    @Test
    fun `balanced brackets with invalid JSON inside yield no resources`() {
        val result = detector(runnerReturning("[oops not json]")).detect(issue())
        assertThat(result).isEmpty()
    }

    @Test
    fun `an opening bracket with no matching close yields no resources`() {
        val result = detector(runnerReturning("here is a list [ but it never closes")).detect(issue())
        assertThat(result).isEmpty()
    }
}
