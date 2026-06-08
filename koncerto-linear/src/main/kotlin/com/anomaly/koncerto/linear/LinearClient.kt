package com.anomaly.koncerto.linear

import com.anomaly.koncerto.core.model.Issue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface LinearClient {
    suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue>
    suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue>
    suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String>
}

class DefaultLinearClient(
    private val graphql: LinearGraphQLClient,
    private val projectSlug: String
) : LinearClient {

    internal val candidateQuery = """
        query Candidates(${'$'}projectSlug: String!, ${'$'}states: [String!], ${'$'}first: Int!, ${'$'}after: String) {
          issues(filter: { project: { slugId: { eq: ${'$'}projectSlug } }, state: { name: { in: ${'$'}states } } }, first: ${'$'}first, after: ${'$'}after) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id identifier title description priority url branchName createdAt updatedAt
              state { name }
              labels { nodes { name } }
              blockedBy: relations(filter: { type: { eq: "blocks" } }) {
                nodes {
                  ... on Issue {
                    id identifier
                    state { name }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()

    internal val issuesByStatesQuery = """
        query IssuesByStates(${'$'}projectSlug: String!, ${'$'}states: [String!], ${'$'}first: Int!) {
          issues(filter: { project: { slugId: { eq: ${'$'}projectSlug } }, state: { name: { in: ${'$'}states } } }, first: ${'$'}first) {
            pageInfo { hasNextPage endCursor }
            nodes { id identifier title description priority url branchName createdAt updatedAt
              state { name }
              labels { nodes { name } }
            }
          }
        }
    """.trimIndent()

    internal val statesByIdsQuery = """
        query StatesByIds(${'$'}ids: [ID!]!) {
          nodes(filter: { id: { in: ${'$'}ids } }) {
            ... on Issue { id state { name } }
          }
        }
    """.trimIndent()

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> {
        if (activeStates.isEmpty()) return emptyList()
        val all = mutableListOf<Issue>()
        var after: String? = null
        do {
            val vars = buildJsonObject {
                put("projectSlug", projectSlug)
                put("states", buildJsonArray { activeStates.forEach { add(it) } })
                put("first", 50)
                if (after != null) put("after", after)
            }
            val resp = graphql.execute(candidateQuery, vars)
            val dataObj = resp["data"] as? JsonObject ?: throw LinearError.UnknownPayload()
            val conn = dataObj["issues"] as? JsonObject
                ?: throw LinearError.UnknownPayload()
            val pageInfo = conn["pageInfo"] as? JsonObject ?: throw LinearError.UnknownPayload()
            val nodes = conn["nodes"] as? JsonArray ?: throw LinearError.UnknownPayload()
            nodes.forEach { all += IssueMapper.fromLinear(it as JsonObject) }
            val hasNext = (pageInfo["hasNextPage"] as? JsonPrimitive)?.content?.toBoolean() ?: false
            after = (pageInfo["endCursor"] as? JsonPrimitive)?.content
            if (hasNext && after == null) throw LinearError.MissingEndCursor()
        } while (after != null)
        return all
    }

    override suspend fun fetchIssuesByStates(projectSlug: String, stateNames: List<String>): List<Issue> {
        if (stateNames.isEmpty()) return emptyList()
        val vars = buildJsonObject {
            put("projectSlug", projectSlug)
            put("states", buildJsonArray { stateNames.forEach { add(it) } })
            put("first", 50)
        }
        val resp = graphql.execute(issuesByStatesQuery, vars)
        val conn = (resp["data"] as JsonObject)["issues"] as? JsonObject ?: throw LinearError.UnknownPayload()
        val nodes = conn["nodes"] as? JsonArray ?: throw LinearError.UnknownPayload()
        return nodes.map { IssueMapper.fromLinear(it as JsonObject) }
    }

    override suspend fun fetchIssueStatesByIds(issueIds: List<String>): Map<String, String> {
        if (issueIds.isEmpty()) return emptyMap()
        val vars = buildJsonObject {
            put("ids", buildJsonArray { issueIds.forEach { add(it) } })
        }
        val resp = graphql.execute(statesByIdsQuery, vars)
        val nodes = (resp["data"] as JsonObject)["nodes"] as? JsonArray
            ?: throw LinearError.UnknownPayload()
        val map = mutableMapOf<String, String>()
        nodes.forEach {
            val obj = it as JsonObject
            val id = (obj["id"] as? JsonPrimitive)?.content ?: ""
            val stateObj = obj["state"] as? JsonObject
            val state = (stateObj?.get("name") as? JsonPrimitive)?.content
            if (state != null) map[id] = state
        }
        return map
    }
}
