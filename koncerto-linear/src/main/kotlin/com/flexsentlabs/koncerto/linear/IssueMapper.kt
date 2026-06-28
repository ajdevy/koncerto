package com.flexsentlabs.koncerto.linear

import com.flexsentlabs.koncerto.core.model.BlockerRef
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.model.UserRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

object IssueMapper {

    fun fromLinear(node: JsonObject): Issue {
        val id = node.string("id")
        val identifier = node.string("identifier")
        val title = node.string("title")
        val description = node.optionalString("description")
        val priority = (node["priority"] as? JsonPrimitive)?.content?.toIntOrNull()
        val state = (node["state"] as? JsonObject)?.string("name") ?: "Unknown"
        val branchName = (node["branchName"] as? JsonPrimitive)?.content
        val url = node.optionalString("url")
        
        val labelsNodes = (node["labels"] as? JsonObject)?.get("nodes") as? JsonArray
        val labels = labelsNodes?.mapNotNull { (it as? JsonObject)?.stringOrNull("name") }
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        
        val childrenNodes = (node["children"] as? JsonObject)?.get("nodes") as? JsonArray
        val children = childrenNodes?.mapNotNull { (it as? JsonObject)?.stringOrNull("id") } ?: emptyList()

        val blockedByNodes = (node["blockedBy"] as? JsonObject)?.get("nodes") as? JsonArray
        val blockedBy = blockedByNodes?.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val relationType = obj.optionalString("type") ?: return@mapNotNull null
            if (!relationType.equals("blocks", ignoreCase = true)) return@mapNotNull null
            val issueNode = obj["issue"] as? JsonObject ?: return@mapNotNull null
            val s = issueNode["state"] as? JsonObject
            BlockerRef(
                id = issueNode.optionalString("id"),
                identifier = issueNode.optionalString("identifier"),
                state = s?.stringOrNull("name")
            )
        } ?: emptyList()
        
        val creator: UserRef? = (node["creator"] as? JsonObject)?.let { creatorObj ->
            UserRef(
                id = creatorObj.string("id"),
                displayName = creatorObj.string("displayName"),
                isBot = (creatorObj["isBot"] as? JsonPrimitive)?.content?.toBoolean() ?: false
            )
        }
        val createdAt = node.optionalString("createdAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val updatedAt = node.optionalString("updatedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return Issue(
            id = id,
            identifier = identifier,
            title = title,
            description = description,
            priority = priority,
            state = state,
            branchName = branchName,
            url = url,
            labels = labels,
            blockedBy = blockedBy,
            creator = creator,
            createdAt = createdAt,
            updatedAt = updatedAt,
            children = children
        )
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: error("missing field $key")

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content