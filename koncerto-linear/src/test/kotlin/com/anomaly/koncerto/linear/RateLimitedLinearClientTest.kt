package com.anomaly.koncerto.linear

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.CircuitBreaker
import com.anomaly.koncerto.core.TokenBucketRateLimiter
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.core.model.UserRef
import com.anomaly.koncerto.logging.StructuredLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RateLimitedLinearClientTest {

    @Test
    fun `successful calls pass through to delegate`() = runBlocking {
        val expected = sampleIssue("1", "ABC-1")
        val fake = FakeLinearClient(issues = listOf(expected))
        val client = RateLimitedLinearClient(fake, null, null, StructuredLogger(emptyList()))

        val result = client.fetchCandidateIssues("slug", listOf("Todo"))

        assertThat(result).isEqualTo(listOf(expected))
        assertThat(fake.callCount["fetchCandidateIssues"]).isEqualTo(1)
    }

    @Test
    fun `failed calls are recorded by circuit breaker`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 2, resetTimeoutMs = 60000)
        val fake = FakeLinearClient(succeed = false)
        val client = RateLimitedLinearClient(fake, null, cb, StructuredLogger(emptyList()))

        assertThrows<RuntimeException> { client.fetchCandidateIssues("s", listOf("a")) }
        assertThat(cb.allowRequest()).isTrue()

        assertThrows<RuntimeException> { client.fetchCandidateIssues("s", listOf("a")) }
        assertThat(cb.allowRequest()).isFalse()
    }

    @Test
    fun `rate limiter is invoked`() = runBlocking {
        val rl = TokenBucketRateLimiter(maxTokens = 1, refillIntervalMs = 60000, refillCount = 1)
        val fake = FakeLinearClient()
        val client = RateLimitedLinearClient(fake, rl, null, StructuredLogger(emptyList()))

        client.fetchCandidateIssues("slug", listOf("a"))

        val timedOut = withTimeoutOrNull(100) {
            client.fetchCandidateIssues("slug", listOf("a"))
            "done"
        }
        assertThat(timedOut).isNull()
    }

    @Test
    fun `circuit breaker open state throws RateLimited`() = runBlocking {
        val cb = CircuitBreaker(failureThreshold = 1, resetTimeoutMs = 60000)
        val fake = FakeLinearClient(succeed = false)
        val client = RateLimitedLinearClient(fake, null, cb, StructuredLogger(emptyList()))

        assertThrows<RuntimeException> { client.fetchCandidateIssues("s", listOf("a")) }

        val ex = assertThrows<LinearError.RateLimited> {
            client.fetchCandidateIssues("s", listOf("a"))
        }
        assertThat(ex.message).isEqualTo("linear_rate_limited: Circuit breaker open")
    }

    @Test
    fun `null rateLimiter and null circuitBreaker act as pass-through`() = runBlocking {
        val expected = sampleIssue("1", "ABC-1")
        val fake = FakeLinearClient(issues = listOf(expected))
        val client = RateLimitedLinearClient(fake, null, null, StructuredLogger(emptyList()))

        val result = client.fetchCandidateIssues("slug", listOf("Todo"))
        assertThat(result).isEqualTo(listOf(expected))
    }

    @Test
    fun `null rateLimiter and null circuitBreaker propagate exceptions`() = runBlocking {
        val fake = FakeLinearClient(succeed = false)
        val client = RateLimitedLinearClient(fake, null, null, StructuredLogger(emptyList()))

        assertThrows<RuntimeException> { client.fetchCandidateIssues("s", listOf("a")) }
    }

    private fun sampleIssue(id: String, identifier: String, state: String = "Todo") = Issue(
        id = id, identifier = identifier, title = "t", description = null,
        priority = 1, state = state, branchName = null, url = null,
        labels = emptyList(), blockedBy = emptyList(), createdAt = null, updatedAt = null
    )
}

class FakeLinearClient(
    val succeed: Boolean = true,
    val issues: List<Issue> = emptyList()
) : LinearClient {
    val callCount = mutableMapOf<String, Int>()

    private fun record(name: String) {
        callCount[name] = (callCount[name] ?: 0) + 1
    }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> {
        record("fetchCandidateIssues"); if (!succeed) throw RuntimeException("API error"); return issues
    }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> {
        record("fetchIssuesByStates"); if (!succeed) throw RuntimeException("API error"); return issues
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> {
        record("fetchIssueStatesByIds"); if (!succeed) throw RuntimeException("API error"); return emptyMap()
    }

    override suspend fun fetchIssueById(issueId: String): Issue? {
        record("fetchIssueById"); if (!succeed) throw RuntimeException("API error"); return null
    }

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? {
        record("resolveStateId"); if (!succeed) throw RuntimeException("API error"); return null
    }

    override suspend fun updateIssueState(issueId: String, stateId: String) {
        record("updateIssueState"); if (!succeed) throw RuntimeException("API error")
    }

    override suspend fun createComment(issueId: String, body: String) {
        record("createComment"); if (!succeed) throw RuntimeException("API error")
    }

    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {
        record("updateIssueAssignee"); if (!succeed) throw RuntimeException("API error")
    }

    override suspend fun fetchIssueCreator(issueId: String): UserRef? {
        record("fetchIssueCreator"); if (!succeed) throw RuntimeException("API error"); return null
    }

    override suspend fun createIssue(
        projectSlug: String, title: String, state: String,
        description: String?, labels: List<String>
    ): Issue? {
        record("createIssue"); if (!succeed) throw RuntimeException("API error"); return null
    }

    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String): Boolean {
        record("createLink"); if (!succeed) throw RuntimeException("API error"); return false
    }
}
