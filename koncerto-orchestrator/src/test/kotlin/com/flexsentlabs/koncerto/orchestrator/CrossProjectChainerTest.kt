package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.CrossProjectFollowUpConfig
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.TrackerConfig
import com.flexsentlabs.koncerto.core.config.WorkspaceConfig
import com.flexsentlabs.koncerto.core.config.AgentProjectConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class CrossProjectChainerTest {

    private fun testIssue(id: String = "iss-1", identifier: String = "PROJ-1", title: String = "Test Issue") = Issue(
        id = id, identifier = identifier, title = title, description = null,
        priority = 5, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )

    @Test
    fun `creates follow-up issue in target project`() {
        val trackingClient = TrackingClient()
        val chainer = createChainer { slug -> if (slug == "target-proj") trackingClient else null }
        val issue = testIssue()
        val config = CrossProjectFollowUpConfig(
            targetProjectSlug = "target-proj",
            titleTemplate = "Follow-up for {sourceId}: {sourceTitle}",
            descriptionTemplate = "Created from {sourceProject}",
            linkType = "blocks"
        )

        runBlocking {
            chainer.createFollowUp(issue, config, "source-proj")
        }

        assertThat(trackingClient.createdTitle).isEqualTo("Follow-up for PROJ-1: Test Issue")
        assertThat(trackingClient.createdDescription).isEqualTo("Created from source-proj")
        assertThat(trackingClient.createdProjectSlug).isEqualTo("target-proj")
    }

    @Test
    fun `cycle detection prevents duplicate chains`() {
        val trackingClient = TrackingClient()
        val chainer = createChainer { slug -> if (slug == "target-proj") trackingClient else null }
        val issue = testIssue()
        val config = CrossProjectFollowUpConfig(
            targetProjectSlug = "target-proj",
            titleTemplate = "Follow-up for {sourceId}"
        )

        runBlocking {
            chainer.createFollowUp(issue, config, "source-proj")
            chainer.createFollowUp(issue, config, "source-proj")
        }

        assertThat(trackingClient.createCount).isEqualTo(1)
    }

    @Test
    fun `max depth enforced`() {
        val trackingClient = TrackingClient()
        val chainer = DefaultCrossProjectChainer(
            config = ServiceConfig(),
            linearClientProvider = { slug -> if (slug == "target-proj") trackingClient else null },
            logger = StructuredLogger(emptyList()),
            maxDepth = 2
        )
        val issue = testIssue()
        val config = CrossProjectFollowUpConfig(
            targetProjectSlug = "target-proj",
            titleTemplate = "Follow-up {sourceId}"
        )

        runBlocking {
            chainer.createFollowUp(issue, config, "source-proj")
            val issue2 = testIssue(id = "iss-2", identifier = "PROJ-2")
            chainer.createFollowUp(issue2, config, "source-proj")
            val issue3 = testIssue(id = "iss-3", identifier = "PROJ-3")
            chainer.createFollowUp(issue3, config, "source-proj")
        }

        assertThat(trackingClient.createCount).isEqualTo(3)
    }

    @Test
    fun `failure does not propagate`() {
        val chainer = createChainer { slug -> null }
        val issue = testIssue()
        val config = CrossProjectFollowUpConfig(
            targetProjectSlug = "nonexistent",
            titleTemplate = "Follow-up"
        )

        var caught = false
        runBlocking {
            try {
                chainer.createFollowUp(issue, config, "source-proj")
            } catch (_: Exception) {
                caught = true
            }
        }

        assertThat(caught).isFalse()
    }

    @Test
    fun `template variables are substituted`() {
        val trackingClient = TrackingClient()
        val chainer = createChainer { slug -> if (slug == "target") trackingClient else null }
        val issue = testIssue(id = "abc-123", identifier = "KONC-42", title = "Important fix")

        runBlocking {
            chainer.createFollowUp(
                issue,
                CrossProjectFollowUpConfig(
                    targetProjectSlug = "target",
                    titleTemplate = "[{sourceProject}] {sourceTitle} ({sourceId})"
                ),
                "main-app"
            )
        }

        assertThat(trackingClient.createdTitle).isEqualTo("[main-app] Important fix (KONC-42)")
    }

    private fun createChainer(provider: (String) -> TrackerClient?): DefaultCrossProjectChainer {
        return DefaultCrossProjectChainer(
            config = ServiceConfig(),
            linearClientProvider = provider,
            logger = StructuredLogger(emptyList()),
            maxDepth = 3
        )
    }

    private class TrackingClient : TrackerClient {
        var createdTitle: String? = null
        var createdDescription: String? = null
        var createdProjectSlug: String? = null
        var createCount = 0

        override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> = emptyList()
        override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> = emptyList()
        override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> = emptyMap()
        override suspend fun fetchIssueById(issueId: String): Issue? = null
        override suspend fun resolveStateId(projectSlug: String, stateName: String): String? = null
        override suspend fun updateIssueState(issueId: String, stateId: String) {}
        override suspend fun createComment(issueId: String, body: String) {}
        override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {}
        override suspend fun fetchIssueCreator(issueId: String) = null
        override suspend fun createIssue(
            projectSlug: String, title: String, state: String,
            description: String?, labels: List<String>
        ): Issue? {
            createdTitle = title
            createdDescription = description
            createdProjectSlug = projectSlug
            createCount++
            return com.flexsentlabs.koncerto.core.model.Issue(
                id = "new-$createCount", identifier = "TARGET-$createCount", title = title,
                description = description, priority = 5, state = "Todo", branchName = null,
                url = null, labels = emptyList(), blockedBy = emptyList(),
                createdAt = null, updatedAt = null
            )
        }
        override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean = true
    }
}
