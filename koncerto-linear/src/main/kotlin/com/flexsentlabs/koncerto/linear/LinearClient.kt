package com.flexsentlabs.koncerto.linear

import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias LinearClient = TrackerClient

class DefaultLinearClient(
    private val graphql: LinearGraphQLClient,
    private val projectSlug: String
) : LinearClient {

    internal fun candidateQuery(after: String? = null): String {
        val afterClause = if (after != null) ", after: \"$after\"" else ""
        return """
        query Candidates(${'$'}projectSlug: String!, ${'$'}states: [String!], ${'$'}first: Int!) {
          issues(filter: { project: { slugId: { eq: ${'$'}projectSlug } }, state: { name: { in: ${'$'}states } } }, first: ${'$'}first$afterClause) {
            pageInfo { hasNextPage endCursor }
            nodes {
              id identifier title description priority url branchName createdAt updatedAt
              state { name }
              labels { nodes { name } }
              blockedBy: inverseRelations(first: 25) {
                nodes {
                  type
                  issue {
                    id identifier
                    state { name }
                  }
                }
              }
            }
          }
        }
    """.trimIndent()
    }

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
            creator { id displayName isBot }
            blockedBy: inverseRelations(first: 25) {
              nodes {
                type
                issue { id identifier state { name } }
              }
            }
          }
        }
    """.trimIndent()

    internal val teamStatesQuery = """
        query TeamStates(${'$'}projectSlug: String!) {
          project(id: ${'$'}projectSlug) {
            teams { nodes { states { nodes { id name } } } }
          }
        }
    """.trimIndent()

    internal val updateIssueStateMutation = """
        mutation IssueUpdate(${'$'}id: String!, ${'$'}stateId: String!) {
          issueUpdate(id: ${'$'}id, input: { stateId: ${'$'}stateId }) { success }
        }
    """.trimIndent()

    internal val createCommentMutation = """
        mutation CommentCreate(${'$'}issueId: String!, ${'$'}body: String!) {
          commentCreate(input: { issueId: ${'$'}issueId, body: ${'$'}body }) { success }
        }
    """.trimIndent()

    internal val updateIssueAssigneeMutation = """
        mutation IssueAssigneeUpdate(${'$'}id: String!, ${'$'}assigneeId: String!) {
          issueUpdate(id: ${'$'}id, input: { assigneeId: ${'$'}assigneeId }) { success }
        }
    """.trimIndent()

    internal val teamIdQuery = """
        query TeamId(${'$'}projectSlug: String!) {
          project(slugId: ${'$'}projectSlug) {
            team { id }
          }
        }
    """.trimIndent()

    internal val createIssueMutation = """
        mutation IssueCreate(${'$'}teamId: String!, ${'$'}title: String!, ${'$'}stateId: String, ${'$'}description: String) {
          issueCreate(input: { teamId: ${'$'}teamId, title: ${'$'}title, stateId: ${'$'}stateId, description: ${'$'}description }) {
            success
            issue { id identifier title state { name } }
          }
        }
    """.trimIndent()

    internal val createLinkMutation = """
        mutation RelationCreate(${'$'}issueId: String!, ${'$'}relatedIssueId: String!, ${'$'}type: String!) {
          issueRelationCreate(issue: {id: ${'$'}issueId}, relatedIssueId: ${'$'}relatedIssueId, type: ${'$'}type) { success }
        }
    """.trimIndent()

    override suspend fun fetchCandidateIssues(projectSlug: String, activeStates: List<String>): List<Issue> {
        if (activeStates.isEmpty()) return emptyList()
        val all = mutableListOf<Issue>()
        var after: String? = null
        do {
            val vars = buildJsonObject {
                put("projectSlug", this@DefaultLinearClient.projectSlug)
                put("states", buildJsonArray { activeStates.forEach { add(it) } })
                put("first", 50)
            }
            val resp = graphql.execute(candidateQuery(after), vars)
            val dataObj = resp["data"] as? JsonObject ?: throw LinearError.UnknownPayload()
            val conn = dataObj["issues"] as? JsonObject
                ?: throw LinearError.UnknownPayload()
            val pageInfo = conn["pageInfo"] as? JsonObject ?: throw LinearError.UnknownPayload()
            val nodes = conn["nodes"] as? JsonArray ?: throw LinearError.UnknownPayload()
            nodes.forEach { all += IssueMapper.fromLinear(it as JsonObject) }
            val hasNext = (pageInfo["hasNextPage"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toBoolean() ?: false
            after = (pageInfo["endCursor"] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
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
        val teams = (project["teams"] as? JsonObject)?.get("nodes") as? JsonArray ?: return null
        for (teamNode in teams) {
            val team = teamNode as? JsonObject ?: continue
            val statesObj = team["states"] as? JsonObject ?: continue
            val states = statesObj["nodes"] as? JsonArray ?: continue
            for (node in states) {
                val obj = node as? JsonObject ?: continue
                val name = (obj["name"] as? JsonPrimitive)?.content ?: continue
                if (name.equals(stateName, ignoreCase = true)) {
                    return (obj["id"] as? JsonPrimitive)?.content
                }
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

    override suspend fun createComment(issueId: String, body: String) {
        val vars = buildJsonObject {
            put("issueId", issueId)
            put("body", body)
        }
        graphql.execute(createCommentMutation, vars)
    }

    override suspend fun updateIssueAssignee(issueId: String, assigneeId: String) {
        val vars = buildJsonObject {
            put("id", issueId)
            put("assigneeId", assigneeId)
        }
        graphql.execute(updateIssueAssigneeMutation, vars)
    }

    override suspend fun fetchIssueCreator(issueId: String): UserRef? {
        val issue = fetchIssueById(issueId)
        return issue?.creator
    }

    internal suspend fun resolveTeamId(projectSlug: String): String? {
        val vars = buildJsonObject { put("projectSlug", projectSlug) }
        val resp = graphql.execute(teamIdQuery, vars)
        val project = (resp["data"] as? JsonObject)?.get("project") as? JsonObject ?: return null
        val team = project["team"] as? JsonObject ?: return null
        return (team["id"] as? JsonPrimitive)?.content
    }

    override suspend fun createIssue(
        projectSlug: String,
        title: String,
        state: String,
        description: String?,
        labels: List<String>
    ): Issue? {
        return try {
            val teamId = resolveTeamId(projectSlug) ?: return null
            val stateId = resolveStateId(projectSlug, state)
            val vars = buildJsonObject {
                put("teamId", teamId)
                put("title", title)
                if (description != null) put("description", description)
                if (stateId != null) put("stateId", stateId)
            }
            val resp = graphql.execute(createIssueMutation, vars)
            parseCreatedIssue(resp)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun createLink(
        sourceIssueId: String,
        targetIssueId: String,
        type: String
    ): Boolean {
        return try {
            val vars = buildJsonObject {
                put("issueId", sourceIssueId)
                put("relatedIssueId", targetIssueId)
                put("type", type)
            }
            val resp = graphql.execute(createLinkMutation, vars)
            (resp["data"] as? JsonObject)?.get("issueRelationCreate") != null
        } catch (e: Exception) {
            false
        }
    }

    private fun parseCreatedIssue(response: JsonObject): Issue? {
        val node = (response["data"] as? JsonObject)?.get("issueCreate") as? JsonObject ?: return null
        val issue = node["issue"] as? JsonObject ?: return null
        val success = (node["success"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        if (!success) return null
        return IssueMapper.fromLinear(issue)
    }
}
