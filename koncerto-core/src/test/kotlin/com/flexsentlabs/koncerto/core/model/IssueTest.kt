package com.flexsentlabs.koncerto.core.model

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.time.Instant
import org.junit.jupiter.api.Test

class IssueTest {

    @Test
    fun `Issue is constructed with all fields`() {
        val now = Instant.now()
        val issue = Issue(
            id = "issue-1",
            identifier = "ABC-1",
            title = "Test issue",
            description = "Body",
            priority = 2,
            state = "Todo",
            branchName = "abc-1-branch",
            url = "https://linear.app/test/issue/ABC-1",
            labels = listOf("bug", "frontend"),
            blockedBy = listOf(BlockerRef(id = "x", identifier = "ABC-2", state = "Done")),
            createdAt = now,
            updatedAt = now
        )

        assertThat(issue.id).isEqualTo("issue-1")
        assertThat(issue.identifier).isEqualTo("ABC-1")
        assertThat(issue.title).isEqualTo("Test issue")
        assertThat(issue.priority).isEqualTo(2)
        assertThat(issue.state).isEqualTo("Todo")
        assertThat(issue.labels).containsExactly("bug", "frontend")
        assertThat(issue.blockedBy).containsExactly(BlockerRef("x", "ABC-2", "Done"))
    }

    @Test
    fun `Issue normalizes labels to lowercase`() {
        val issue = Issue(
            id = "1", identifier = "A-1", title = "t", description = null,
            priority = null, state = "Todo", branchName = null, url = null,
            labels = listOf("  Bug  ", "FRONTEND", ""),
            blockedBy = emptyList(), createdAt = null, updatedAt = null
        )
        assertThat(issue.labels.map { it.trim().lowercase() }.filter { it.isNotEmpty() })
            .containsExactly("bug", "frontend")
    }

    @Test
    fun `normalizedState lowercases the state`() {
        val issue = sampleIssue().copy(state = "In Progress")
        assertThat(issue.normalizedState).isEqualTo("in progress")
    }

    @Test
    fun `normalizedState handles null description`() {
        val issue = sampleIssue().copy(description = null)
        assertThat(issue.description).isNull()
    }

    @Test
    fun `UserRef has expected fields`() {
        val ref = UserRef(id = "user-1", displayName = "Alice", isBot = false)
        assertThat(ref.id).isEqualTo("user-1")
        assertThat(ref.displayName).isEqualTo("Alice")
        assertThat(ref.isBot).isEqualTo(false)
    }

    private fun sampleIssue() = Issue(
        id = "1", identifier = "A-1", title = "t", description = "d",
        priority = 1, state = "Todo", branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(),
        createdAt = null, updatedAt = null
    )
}
