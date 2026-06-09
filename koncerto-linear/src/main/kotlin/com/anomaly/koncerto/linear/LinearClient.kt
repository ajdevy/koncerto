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
    suspend fun fetchIssueById(issueId: String): Issue?
    suspend fun resolveStateId(projectSlug: String, stateName: String): String?
    suspend fun updateIssueState(issueId: String, stateId: String)
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

    internal val issueByIdQuery = """
        query IssueById(${'$'}id: String!) {
          issue(id: ${'$'}id) {
            id identifier title description priority url branchName createdAt updatedAt
            state { name }
            labels { nodes { name } }
            blockedBy: relations(filter: { type: { eq: "blocks" } }) {
              nodes {
                ... on Issue { id identifier state { name } }
              }
            }
          }
        }
    """.trimIndent()

    internal val teamStatesQuery = """
        query TeamStates(${'$'}projectSlug: String!) {
          project(slugId: ${'$'}projectSlug) {
            team { states { nodes { id name } } }
          }
        }
    """.trimIndent()

    internal val updateIssueStateMutation = """
        mutation IssueUpdate(${'$'}id: String!, ${'$'}stateId: String!) {
          issueUpdate(id: ${'$'}id, input: { stateId: ${'$'}stateId }) { success }
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

    override suspend fun fetchIssueById(issueId: String): Issue? {
        val vars = buildJsonObject { put("id", issueId) }
        val resp = graphql.execute(issueByIdQuery, vars)
        val issueNode = (resp["data"] as? JsonObject)?.get("issue") as? JsonObject ?: return null
        return IssueMapper.fromLinear(issueNode)
    }

    override suspend fun resolveStateId(projectSlug: String, stateName: String): String? {
        val vars = buildJsonObject { put("projectSlug", projectSlug) }
        val resp = graphql.execute(teamStatesQuery, vars)
        val project = (resp["data"] as? JsonObject)?.get("project") as? JsonObject ?: return null
        val team = project["team"] as? JsonObject ?: return null
        val states = (team["states"] as? JsonObject)?.get("nodes") as? JsonArray ?: return null
        for (node in states) {
            val obj = node as? JsonObject ?: continue
            val name = (obj["name"] as? JsonPrimitive)?.content ?: continue
            if (name.equals(stateName, ignoreCase = true)) {
                return (obj["id"] as? JsonPrimitive)?.content
            }
        }
        return null
    }

    override suspend fun updateIssueState(issueId: String, stateId: String) {
        val vars = buildJsonObject {
            put("id", issueId)
            put("stateId", stateId)
        }
        graphql.execute(updateIssueStateMutation, vars)
    }
}
