package com.flexsentlabs.koncerto.core.lifecycle

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class IssueLifecycleTest {

    @Test
    fun `all 4 lifecycle states are instances`() {
        val states: List<IssueLifecycle> = listOf(
            IssueLifecycle.Todo,
            IssueLifecycle.InProgress,
            IssueLifecycle.InReview,
            IssueLifecycle.Done
        )
        assertThat(states.size).isEqualTo(4)
    }

    @Test
    fun `normalizedName returns lowercase correct values`() {
        assertThat(IssueLifecycle.Todo.normalizedName).isEqualTo("todo")
        assertThat(IssueLifecycle.InProgress.normalizedName).isEqualTo("in progress")
        assertThat(IssueLifecycle.InReview.normalizedName).isEqualTo("in review")
        assertThat(IssueLifecycle.Done.normalizedName).isEqualTo("done")
    }

    @Test
    fun `fromNormalized correctly parses todo`() {
        assertThat(IssueLifecycle.fromNormalized("todo")).isEqualTo(IssueLifecycle.Todo)
    }

    @Test
    fun `fromNormalized correctly parses in progress`() {
        assertThat(IssueLifecycle.fromNormalized("in progress")).isEqualTo(IssueLifecycle.InProgress)
    }

    @Test
    fun `fromNormalized correctly parses in review`() {
        assertThat(IssueLifecycle.fromNormalized("in review")).isEqualTo(IssueLifecycle.InReview)
    }

    @Test
    fun `fromNormalized correctly parses done`() {
        assertThat(IssueLifecycle.fromNormalized("done")).isEqualTo(IssueLifecycle.Done)
    }

    @Test
    fun `fromNormalized returns null for unknown names`() {
        assertThat(IssueLifecycle.fromNormalized("unknown")).isNull()
    }

    @Test
    fun `validate Todo to InProgress returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.Todo, IssueLifecycle.InProgress)).isTrue()
    }

    @Test
    fun `validate Todo to InReview returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.Todo, IssueLifecycle.InReview)).isTrue()
    }

    @Test
    fun `validate InProgress to InReview returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.InProgress, IssueLifecycle.InReview)).isTrue()
    }

    @Test
    fun `validate InReview to Done returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.InReview, IssueLifecycle.Done)).isTrue()
    }

    @Test
    fun `validate Todo to Done returns false`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.Todo, IssueLifecycle.Done)).isFalse()
    }

    @Test
    fun `validate Done to Todo returns false`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.Done, IssueLifecycle.Todo)).isFalse()
    }

    @Test
    fun `validate InProgress to Todo returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.InProgress, IssueLifecycle.Todo)).isTrue()
    }

    @Test
    fun `validate InReview to Todo returns true`() {
        assertThat(IssueLifecycle.validate(IssueLifecycle.InReview, IssueLifecycle.Todo)).isTrue()
    }
}
