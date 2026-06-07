package com.anomaly.koncerto.linear

import com.anomaly.koncerto.core.model.BlockerRef
import com.anomaly.koncerto.core.model.Issue
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
        
        val blockedByNodes = (node["blockedBy"] as? JsonObject)?.get("nodes") as? JsonArray
        val blockedBy = blockedByNodes?.mapNotNull { node ->
            val obj = node as? JsonObject ?: return@mapNotNull null
            val s = obj["state"] as? JsonObject
            BlockerRef(
                id = obj.optionalString("id"),
                identifier = obj.optionalString("identifier"),
                state = s?.stringOrNull("name")
            )
        } ?: emptyList()
        
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
            createdAt = createdAt,
            updatedAt = updatedAt
        )
    }
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.content ?: error("missing field $key")

private fun JsonObject.optionalString(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.content