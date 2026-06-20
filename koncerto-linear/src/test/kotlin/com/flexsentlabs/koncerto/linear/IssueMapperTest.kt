package com.flexsentlabs.koncerto.linear

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class IssueMapperTest {

    @Test
    fun `maps Linear node to Issue`() {
        val stateObj = buildJsonObject { put("name", "Todo") }
        val bugLabel = buildJsonObject { put("name", "  Bug  ") }
        val frontendLabel = buildJsonObject { put("name", "FRONTEND") }
        val labelsNodes = buildJsonArray { add(bugLabel); add(frontendLabel) }
        val labelsObj = buildJsonObject { put("nodes", labelsNodes) }
        
        val blockedStateObj = buildJsonObject { put("name", "Done") }
        val blockedIssueObj = buildJsonObject {
            put("id", "x")
            put("identifier", "ABC-2")
            put("state", blockedStateObj)
        }
        val blockedByNodes = buildJsonArray { add(blockedIssueObj) }
        val blockedByObj = buildJsonObject { put("nodes", blockedByNodes) }
        
        val childrenNodes = buildJsonArray { add(buildJsonObject { put("id", "sub-1") }) }
        val childrenObj = buildJsonObject { put("nodes", childrenNodes) }

        val node = buildJsonObject {
            put("id", "id-1")
            put("identifier", "ABC-1")
            put("title", "Test")
            put("description", "Body")
            put("priority", JsonPrimitive(2))
            put("state", stateObj)
            put("url", "https://linear.app/x")
            put("labels", labelsObj)
            put("children", childrenObj)
            put("blockedBy", blockedByObj)
            put("createdAt", "2025-01-01T00:00:00Z")
            put("updatedAt", "2025-01-02T00:00:00Z")
        }
        
        val issue = IssueMapper.fromLinear(node)
        assertThat(issue.id).isEqualTo("id-1")
        assertThat(issue.identifier).isEqualTo("ABC-1")
        assertThat(issue.priority).isEqualTo(2)
        assertThat(issue.state).isEqualTo("Todo")
        assertThat(issue.labels).containsExactly("bug", "frontend")
        assertThat(issue.blockedBy).isNotNull()
        assertThat(issue.children).containsExactly("sub-1")
        assertThat(issue.createdAt).isNotNull()
    }

    @Test
    fun `maps children from Linear node`() {
        val childrenNodes = buildJsonArray {
            add(buildJsonObject { put("id", "sub-1") })
            add(buildJsonObject { put("id", "sub-2") })
        }
        val childrenObj = buildJsonObject { put("nodes", childrenNodes) }
        val node = buildJsonObject {
            put("id", "id-1")
            put("identifier", "ABC-1")
            put("title", "Test")
            put("description", null)
            put("priority", JsonPrimitive(2))
            put("state", buildJsonObject { put("name", "Todo") })
            put("url", null)
            put("labels", buildJsonObject { put("nodes", buildJsonArray {}) })
            put("children", childrenObj)
            put("blockedBy", buildJsonObject { put("nodes", buildJsonArray {}) })
            put("createdAt", null)
            put("updatedAt", null)
        }
        val issue = IssueMapper.fromLinear(node)
        assertThat(issue.children).containsExactly("sub-1", "sub-2")
    }

    @Test
    fun `maps empty children when children field missing`() {
        val node = buildJsonObject {
            put("id", "id-1")
            put("identifier", "ABC-1")
            put("title", "Test")
            put("description", null)
            put("priority", JsonPrimitive(2))
            put("state", buildJsonObject { put("name", "Todo") })
            put("url", null)
            put("labels", buildJsonObject { put("nodes", buildJsonArray {}) })
            put("blockedBy", buildJsonObject { put("nodes", buildJsonArray {}) })
            put("createdAt", null)
            put("updatedAt", null)
        }
        val issue = IssueMapper.fromLinear(node)
        assertThat(issue.children).isEqualTo(emptyList())
    }
}