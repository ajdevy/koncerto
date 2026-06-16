package com.flexsentlabs.koncerto.core.tracker

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef

interface TrackerClient {
    suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue>
    suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String>
    suspend fun fetchIssueById(issueId: String): Issue?
    suspend fun resolveStateId(projectSlug: String, stateName: String): String?
    suspend fun updateIssueState(issueId: String, stateId: String)
    suspend fun createComment(issueId: String, body: String)
    suspend fun updateIssueAssignee(issueId: String, assigneeId: String)
    suspend fun fetchIssueCreator(issueId: String): UserRef?
    suspend fun createIssue(
        projectSlug: String,
        title: String,
        state: String,
        description: String? = null,
        labels: List<String> = emptyList()
    ): Issue?
    suspend fun createLink(
        sourceIssueId: String,
        targetIssueId: String,
        type: String
    ): Boolean
}

sealed class TrackerError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    object MissingApiKey : TrackerError()
    data class GraphQlErrors(val errors: String) : TrackerError()
    class Request(message: String, cause: Throwable? = null) : TrackerError(message, cause)
    object UnknownPayload : TrackerError()
    object MissingEndCursor : TrackerError()
    data class StateNotFound(val stateName: String) : TrackerError()
}
