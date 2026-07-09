package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import org.junit.jupiter.api.Test

class DemoFailureReporterTest {

    private val logger = StructuredLogger(emptyList())
    private val reporter = DemoFailureReporter(logger)

    @Test
    fun `postFailure handles missing gh gracefully`() {
        reporter.postFailure(42, "owner/nonexistent-repo", "container crashed", logs = "some logs")
    }

    @Test
    fun `postFailure works without logs`() {
        reporter.postFailure(1, "owner/repo", "timeout", logs = null)
    }

    @Test
    fun `postFailure truncates long logs`() {
        val longLogs = "x".repeat(10_000)
        reporter.postFailure(2, "owner/repo", "error", logs = longLogs)
    }

    @Test
    fun `postFailure handles blank error message`() {
        reporter.postFailure(3, "owner/repo", "", logs = null)
    }

    @Test
    fun `postFailure handles error message with trailing newline`() {
        reporter.postFailure(4, "owner/repo", "container crashed\n", logs = null)
    }
}
