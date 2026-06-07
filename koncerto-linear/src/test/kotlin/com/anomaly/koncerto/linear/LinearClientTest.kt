package com.anomaly.koncerto.linear

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test

class LinearClientTest {

    @Test
    fun `IssueMapper parses issue correctly`() {
        val json = buildJsonObject {
            put("id", JsonPrimitive("id-1"))
            put("identifier", JsonPrimitive("ABC-1"))
            put("title", JsonPrimitive("T1"))
            put("description", JsonNull)
            put("priority", JsonPrimitive(2))
            put("url", JsonNull)
            put("branchName", JsonNull)
            put("createdAt", JsonNull)
            put("updatedAt", JsonNull)
            put("state", buildJsonObject {
                put("name", JsonPrimitive("Todo"))
            })
            put("labels", buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject { put("name", JsonPrimitive("bug")) })
                })
            })
            put("blockedBy", buildJsonObject {
                put("nodes", buildJsonArray {})
            })
        }

        val issue = IssueMapper.fromLinear(json)
        assertThat(issue.id).isEqualTo("id-1")
        assertThat(issue.identifier).isEqualTo("ABC-1")
        assertThat(issue.title).isEqualTo("T1")
        assertThat(issue.state).isEqualTo("Todo")
        assertThat(issue.priority).isEqualTo(2)
        assertThat(issue.labels).isEqualTo(listOf("bug"))
        assertThat(issue.blockedBy.isEmpty()).isTrue()
    }

    @Test
    fun `IssueMapper parses blocker refs`() {
        val json = buildJsonObject {
            put("id", JsonPrimitive("id-1"))
            put("identifier", JsonPrimitive("ABC-1"))
            put("title", JsonPrimitive("T1"))
            put("description", JsonNull)
            put("priority", JsonNull)
            put("url", JsonNull)
            put("branchName", JsonNull)
            put("createdAt", JsonNull)
            put("updatedAt", JsonNull)
            put("state", buildJsonObject {
                put("name", JsonPrimitive("Todo"))
            })
            put("labels", buildJsonObject {
                put("nodes", buildJsonArray {})
            })
            put("blockedBy", buildJsonObject {
                put("nodes", buildJsonArray {
                    add(buildJsonObject {
                        put("id", JsonPrimitive("blocker-1"))
                        put("identifier", JsonPrimitive("ABC-2"))
                        put("state", buildJsonObject {
                            put("name", JsonPrimitive("Done"))
                        })
                    })
                })
            })
        }

        val issue = IssueMapper.fromLinear(json)
        assertThat(issue.blockedBy.size).isEqualTo(1)
        assertThat(issue.blockedBy[0].id).isEqualTo("blocker-1")
        assertThat(issue.blockedBy[0].identifier).isEqualTo("ABC-2")
        assertThat(issue.blockedBy[0].state).isEqualTo("Done")
    }

    @Test
    fun `LinearError types have correct messages`() {
        assertThat(LinearError.MissingApiKey().message).isEqualTo("missing_tracker_api_key")
        assertThat(LinearError.MissingProjectSlug().message).isEqualTo("missing_tracker_project_slug")
        assertThat(LinearError.UnknownPayload().message).isEqualTo("linear_unknown_payload")
        assertThat(LinearError.MissingEndCursor().message).isEqualTo("linear_missing_end_cursor")
        assertThat(LinearError.Status(404).message).isEqualTo("linear_api_status: 404")
        assertThat(LinearError.GraphQlErrors("err").message).isEqualTo("linear_graphql_errors: err")
        assertThat(LinearError.Request("fail").message).isEqualTo("linear_api_request: fail")
    }
}
