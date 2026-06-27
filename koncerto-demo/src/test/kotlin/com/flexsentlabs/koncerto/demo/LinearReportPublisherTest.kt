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
        assert(client.lastBody!!.contains("Demo Recording"))
        assert(client.lastBody!!.contains("https://example.com/recording.webm"))
        assert(client.lastBody!!.contains("KONC-123"))
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
        assert(client.lastBody!!.contains("Demo Recording Failed"))
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
