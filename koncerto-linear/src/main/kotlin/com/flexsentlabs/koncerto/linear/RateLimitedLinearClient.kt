package com.flexsentlabs.koncerto.linear

import com.flexsentlabs.koncerto.core.CircuitBreaker
import com.flexsentlabs.koncerto.core.TokenBucketRateLimiter
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.logging.StructuredLogger

class RateLimitedLinearClient(
    private val delegate: LinearClient,
    private val rateLimiter: TokenBucketRateLimiter?,
    private val circuitBreaker: CircuitBreaker?,
    private val logger: StructuredLogger
) : LinearClient {

    private suspend fun <T> protect(call: suspend () -> T): T {
        if (circuitBreaker != null && !circuitBreaker.allowRequest()) {
            logger.warn("circuit_breaker_open", emptyMap())
            throw LinearError.RateLimited("Circuit breaker open")
        }
        rateLimiter?.acquire()
        return try {
            val result = call()
            circuitBreaker?.recordSuccess()
            result
        } catch (e: Exception) {
            circuitBreaker?.recordFailure()
            throw e
        }
    }

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>) =
        protect { delegate.fetchCandidateIssues(projectSlug, activeStates) }
    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>) =
        protect { delegate.fetchIssuesByStates(projectSlug, stateNames) }
    override suspend fun fetchIssueStatesByIds(issueIds: List<String>) =
        protect { delegate.fetchIssueStatesByIds(issueIds) }
    override suspend fun fetchIssueById(issueId: String) =
        protect { delegate.fetchIssueById(issueId) }
    override suspend fun resolveStateId(projectSlug: String, stateName: String) =
        protect { delegate.resolveStateId(projectSlug, stateName) }
    override suspend fun updateIssueState(issueId: String, stateId: String) =
        protect { delegate.updateIssueState(issueId, stateId) }
    override suspend fun createComment(issueId: String, body: String) =
        protect { delegate.createComment(issueId, body) }
    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) =
        protect { delegate.updateIssueAssignee(issueId, assigneeId) }
    override suspend fun fetchIssueCreator(issueId: String) =
        protect { delegate.fetchIssueCreator(issueId) }
    override suspend fun createIssue(projectSlug: String, title: String, state: String, description: String?, labels: List<String>) =
        protect { delegate.createIssue(projectSlug, title, state, description, labels) }
    override suspend fun createLink(sourceIssueId: String, targetIssueId: String, type: String) =
        protect { delegate.createLink(sourceIssueId, targetIssueId, type) }
}
