package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.report.LinearReportPublisher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class LinearReportPublisherTest {

    private fun createTask(
        id: String = "task-1",
        issueId: String = "issue-1",
        issueIdentifier: String = "KONC-123",
        platform: DemoPlatform = DemoPlatform.PLAYWRIGHT,
        status: DemoStatus = DemoStatus.COMPLETED,
        durationMs: Long? = 5000L,
        fileSizeBytes: Long? = 2048L,
        completedAt: String? = "2026-01-01T00:01:05Z",
        updatedAt: String = "2026-01-01T00:01:05Z",
        retryCount: Int = 0
    ): DemoTask = DemoTask(
        id = id, issueId = issueId, issueIdentifier = issueIdentifier,
        projectSlug = null, platform = platform, status = status,
        trigger = DemoTrigger.MANUAL, createdAt = "2026-01-01T00:00:00Z",
        updatedAt = updatedAt, completedAt = completedAt,
        durationMs = durationMs, fileSizeBytes = fileSizeBytes,
        retryCount = retryCount
    )

    @Test
    fun `report calls createComment on LinearClient`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        val result = publisher.report(task, "https://example.com/recording.webm")
        assert(result is DemoResult.Success)
        assert(client.lastIssueId == "issue-1")
        assert(client.lastBody != null)
        assert(client.lastBody!!.contains("Demo Recorded"))
        assert(client.lastBody!!.contains("https://example.com/recording.webm"))
        assert(client.lastBody!!.contains("KONC-123"))
    }

    @Test
    fun `report renders not-available for null duration only`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask(durationMs = null, fileSizeBytes = 2048L)
        val result = publisher.report(task, "https://example.com/recording.webm")
        assert(result is DemoResult.Success)
        val metadataLine = client.lastBody!!.lines().first { it.startsWith(">") }
        assert(metadataLine.contains("N/A"))
        assert(metadataLine.contains("2 KB"))
    }

    @Test
    fun `report renders not-available for null file size only`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask(durationMs = 5000L, fileSizeBytes = null)
        val result = publisher.report(task, "https://example.com/recording.webm")
        assert(result is DemoResult.Success)
        val metadataLine = client.lastBody!!.lines().first { it.startsWith(">") }
        assert(metadataLine.contains("0m 5s"))
        assert(metadataLine.contains("N/A"))
    }

    @Test
    fun `report renders not-available for both null duration and null file size`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask(durationMs = null, fileSizeBytes = null)
        val result = publisher.report(task, "https://example.com/recording.webm")
        assert(result is DemoResult.Success)
        val metadataLine = client.lastBody!!.lines().first { it.startsWith(">") }
        assert(metadataLine == "> N/A · N/A · [▶ Watch recording](https://example.com/recording.webm)")
    }

    @Test
    fun `report body includes footer`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        publisher.report(task, "https://example.com/recording.webm")
        assert(client.lastBody!!.contains("_Recorded by koncerto_"))
    }

    @Test
    fun `reportFailure with blank error renders fallback text`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        publisher.reportFailure(task, "")
        assert(client.lastBody!!.contains("> _no details provided_"))
    }

    @Test
    fun `reportFailure quoting does not emit a trailing empty blockquote line`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        publisher.reportFailure(task, "error message\n")
        val quotedLines = client.lastBody!!.lines().filter { it.startsWith(">") }
        assert(quotedLines == listOf("> error message"))
    }

    @Test
    fun `report formats sub-kilobyte file size in bytes`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask(fileSizeBytes = 512L)
        publisher.report(task, "https://example.com/recording.webm")
        assert(client.lastBody!!.contains("512 B"))
    }

    @Test
    fun `report formats multi-megabyte file size in MB`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask(fileSizeBytes = 5 * 1024 * 1024L)
        publisher.report(task, "https://example.com/recording.webm")
        assert(client.lastBody!!.contains("5.0 MB"))
    }

    @Test
    fun `reportFailure calls createComment on LinearClient`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        val result = publisher.reportFailure(task, "recording_failed: timeout")
        assert(result is DemoResult.Success)
        assert(client.lastIssueId == "issue-1")
        assert(client.lastBody != null)
        assert(client.lastBody!!.contains("Demo Failed"))
        assert(client.lastBody!!.contains("recording_failed: timeout"))
    }

    @Test
    fun `report wraps LinearClient error in DemoError`() = runTest {
        val client = FakeLinearClient().apply { shouldThrow = true }
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        val result = publisher.report(task, "https://example.com/recording.webm")
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.ReportFailed)
    }

    @Test
    fun `reportFailure wraps LinearClient error in DemoError`() = runTest {
        val client = FakeLinearClient().apply { shouldThrow = true }
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        val result = publisher.reportFailure(task, "recording_failed: timeout")
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.ReportFailed)
    }

    @Test
    fun `reportFailure quotes every line of a multi-line error message`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val task = createTask()
        publisher.reportFailure(task, "line one\nline two")
        val quotedLines = client.lastBody!!.lines().filter { it.startsWith("> ") }
        assert(quotedLines.contains("> line one"))
        assert(quotedLines.contains("> line two"))
    }

    @Test
    fun `reportSkipped calls createComment with skipped body`() = runTest {
        val client = FakeLinearClient()
        val publisher = LinearReportPublisher(client)
        val result = publisher.reportSkipped("issue-1", "KONC-123", "deployment failed and no fallback URL configured")
        assert(result is DemoResult.Success)
        assert(client.lastIssueId == "issue-1")
        assert(client.lastBody!!.contains("Demo Skipped"))
        assert(client.lastBody!!.contains("KONC-123"))
        assert(client.lastBody!!.contains("deployment failed"))
    }

    @Test
    fun `reportSkipped wraps LinearClient error in DemoError`() = runTest {
        val client = FakeLinearClient().apply { shouldThrow = true }
        val publisher = LinearReportPublisher(client)
        val result = publisher.reportSkipped("issue-1", "KONC-123", "reason")
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.ReportFailed)
    }
}

class FakeLinearClient : TrackerClient {
    var lastIssueId: String? = null
    var lastBody: String? = null
    var shouldThrow = false

    override suspend fun createComment(issueId: String, body: String) {
        if (shouldThrow) throw RuntimeException("Linear API error")
        lastIssueId = issueId
        lastBody = body
    }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> = emptyMap()
    override suspend fun fetchIssueById(issueId: String): Issue? = null
    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null
    override suspend fun updateIssueState(issueId: String, stateId: String) {}
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
    override suspend fun fetchIssueCreator(issueId: String): UserRef? = null
    override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>): Issue? = null
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = false
}
