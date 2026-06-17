package com.flexsentlabs.koncerto.notifications

import com.flexsentlabs.koncerto.core.model.TokenUsage
import org.junit.jupiter.api.Test
import assertk.assertThat
import assertk.assertions.*

class NotificationEventTest {

    @Test
    fun `agent completed is constructed with all fields`() {
        val event = NotificationEvent.AgentCompleted(
            projectSlug = "my-project",
            issueId = "123",
            issueIdentifier = "PROJ-123",
            title = "Fix the bug",
            tokenUsage = TokenUsage(inputTokens = 50, outputTokens = 30, totalTokens = 80)
        )
        assertThat(event.projectSlug).isEqualTo("my-project")
        assertThat(event.issueId).isEqualTo("123")
        assertThat(event.issueIdentifier).isEqualTo("PROJ-123")
        assertThat(event.title).isEqualTo("Fix the bug")
        assertThat(event.tokenUsage).isNotNull()
        assertThat(event.tokenUsage!!.inputTokens).isEqualTo(50)
        assertThat(event.tokenUsage!!.outputTokens).isEqualTo(30)
        assertThat(event.tokenUsage!!.totalTokens).isEqualTo(80)
    }

    @Test
    fun `agent completed allows null token usage`() {
        val event = NotificationEvent.AgentCompleted(
            projectSlug = "p",
            issueId = "1",
            issueIdentifier = "P-1",
            title = "t",
            tokenUsage = null
        )
        assertThat(event.tokenUsage).isNull()
    }

    @Test
    fun `agent failed is constructed with all fields including error`() {
        val event = NotificationEvent.AgentFailed(
            projectSlug = "my-project",
            issueId = "456",
            issueIdentifier = "PROJ-456",
            title = "Something broke",
            error = "NullPointerException in processor"
        )
        assertThat(event.projectSlug).isEqualTo("my-project")
        assertThat(event.issueId).isEqualTo("456")
        assertThat(event.issueIdentifier).isEqualTo("PROJ-456")
        assertThat(event.title).isEqualTo("Something broke")
        assertThat(event.error).isEqualTo("NullPointerException in processor")
    }

    @Test
    fun `agent stalled is constructed with all fields including stall duration`() {
        val event = NotificationEvent.AgentStalled(
            projectSlug = "my-project",
            issueId = "789",
            issueIdentifier = "PROJ-789",
            title = "Agent taking too long",
            stallDurationMs = 30_000L
        )
        assertThat(event.projectSlug).isEqualTo("my-project")
        assertThat(event.issueId).isEqualTo("789")
        assertThat(event.issueIdentifier).isEqualTo("PROJ-789")
        assertThat(event.title).isEqualTo("Agent taking too long")
        assertThat(event.stallDurationMs).isEqualTo(30_000L)
    }

    @Test
    fun `clarification requested is constructed with all fields`() {
        val event = NotificationEvent.ClarificationRequested(
            projectSlug = "my-project",
            issueId = "101",
            issueIdentifier = "PROJ-101",
            title = "Need more details"
        )
        assertThat(event.projectSlug).isEqualTo("my-project")
        assertThat(event.issueId).isEqualTo("101")
        assertThat(event.issueIdentifier).isEqualTo("PROJ-101")
        assertThat(event.title).isEqualTo("Need more details")
    }

    @Test
    fun `limit detected is constructed with all fields`() {
        val event = NotificationEvent.LimitDetected(
            projectSlug = "my-project",
            issueId = "789",
            issueIdentifier = "PROJ-789",
            title = "Rate limited",
            errorType = "rate_limit_exceeded",
            summary = "API rate limit exceeded. Retry after 60s.",
            retryAfterMs = 60_000L
        )
        assertThat(event.projectSlug).isEqualTo("my-project")
        assertThat(event.issueId).isEqualTo("789")
        assertThat(event.issueIdentifier).isEqualTo("PROJ-789")
        assertThat(event.title).isEqualTo("Rate limited")
        assertThat(event.errorType).isEqualTo("rate_limit_exceeded")
        assertThat(event.summary).isEqualTo("API rate limit exceeded. Retry after 60s.")
        assertThat(event.retryAfterMs).isEqualTo(60_000L)
    }

    @Test
    fun `limit detected allows null retryAfterMs`() {
        val event = NotificationEvent.LimitDetected(
            projectSlug = "p",
            issueId = "1",
            issueIdentifier = "P-1",
            title = "t",
            errorType = "rate_limit",
            summary = "s",
            retryAfterMs = null
        )
        assertThat(event.retryAfterMs).isNull()
    }

    @Test
    fun `all event types are subclasses of NotificationEvent`() {
        val completed: NotificationEvent = NotificationEvent.AgentCompleted("p", "1", "P-1", "t", null)
        val failed: NotificationEvent = NotificationEvent.AgentFailed("p", "2", "P-2", "t", "err")
        val stalled: NotificationEvent = NotificationEvent.AgentStalled("p", "3", "P-3", "t", 1000L)
        val clarification: NotificationEvent = NotificationEvent.ClarificationRequested("p", "4", "P-4", "t")
        val limit: NotificationEvent = NotificationEvent.LimitDetected("p", "5", "P-5", "t", "rate", "s", null)

        assertThat(completed).isInstanceOf(NotificationEvent.AgentCompleted::class)
        assertThat(failed).isInstanceOf(NotificationEvent.AgentFailed::class)
        assertThat(stalled).isInstanceOf(NotificationEvent.AgentStalled::class)
        assertThat(clarification).isInstanceOf(NotificationEvent.ClarificationRequested::class)
        assertThat(limit).isInstanceOf(NotificationEvent.LimitDetected::class)
    }
}
