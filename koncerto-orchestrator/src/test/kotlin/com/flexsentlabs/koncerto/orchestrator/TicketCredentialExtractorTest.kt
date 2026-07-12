package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.io.File
import org.junit.jupiter.api.Test

class TicketCredentialExtractorTest {

    private fun noopLogger() = StructuredLogger(emptyList())

    private fun issue(title: String = "Register with email code", description: String? = "use ajdev@yandex.com") = Issue(
        id = "i", identifier = "T-1", title = title, description = description,
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    private fun runnerReturning(vararg outputs: String?): DemoScenarioGenerator.ProcessRunner {
        val queue = ArrayDeque(outputs.toList())
        return DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long -> queue.removeFirstOrNull() }
    }

    private fun extractor(runner: DemoScenarioGenerator.ProcessRunner, models: List<String> = listOf("m1")) =
        TicketCredentialExtractor("opencode", noopLogger(), runner, models)

    @Test
    fun `parses a credential map from a JSON array`() {
        val out = """[{"key":"TEST_EMAIL_INBOX","value":"qa@example.com"},{"key":"TEST_EMAIL_IMAP_HOST","value":"imap.example.com"}]"""
        val result = extractor(runnerReturning(out)).extract(issue())
        assertThat(result).isEqualTo(linkedMapOf(
            "TEST_EMAIL_INBOX" to "qa@example.com",
            "TEST_EMAIL_IMAP_HOST" to "imap.example.com"
        ))
    }

    @Test
    fun `tolerates prose and markdown around the JSON array`() {
        val out = "Sure:\n```json\n[{\"key\":\"X_API_KEY\",\"value\":\"abc123\"}]\n```\ndone"
        val result = extractor(runnerReturning(out)).extract(issue())
        assertThat(result).isEqualTo(mapOf("X_API_KEY" to "abc123"))
    }

    @Test
    fun `empty array yields an empty map`() {
        assertThat(extractor(runnerReturning("[]")).extract(issue())).isEmpty()
    }

    @Test
    fun `skips items missing a key or a value`() {
        val out = """[{"key":"","value":"v"},{"key":"K","value":""},{"key":"OK","value":"present"}]"""
        val result = extractor(runnerReturning(out)).extract(issue())
        assertThat(result).isEqualTo(mapOf("OK" to "present"))
    }

    @Test
    fun `malformed reply yields an empty map`() {
        assertThat(extractor(runnerReturning("not json")).extract(issue())).isEmpty()
    }

    @Test
    fun `balanced brackets with invalid JSON yield an empty map`() {
        assertThat(extractor(runnerReturning("[oops]")).extract(issue())).isEmpty()
    }

    @Test
    fun `non-zero exit (null output) yields an empty map`() {
        assertThat(extractor(runnerReturning(null)).extract(issue())).isEmpty()
    }

    @Test
    fun `swallows a runner exception and yields an empty map`() {
        val throwing = DemoScenarioGenerator.ProcessRunner { _: List<String>, _: File, _: Long ->
            throw RuntimeException("opencode blew up")
        }
        assertThat(extractor(throwing).extract(issue())).isEmpty()
    }

    @Test
    fun `cycles to the next model when the first reply is unparseable`() {
        val runner = runnerReturning("garbage", """[{"key":"K","value":"v"}]""")
        val result = extractor(runner, models = listOf("m1", "m2")).extract(issue())
        assertThat(result).isEqualTo(mapOf("K" to "v"))
    }

    @Test
    fun `uses the default model list when none is supplied`() {
        val e = TicketCredentialExtractor("opencode", noopLogger(), runnerReturning("[]"))
        assertThat(e.extract(issue())).isEmpty()
    }
}
