package com.anomaly.koncerto.linear

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.anomaly.koncerto.core.model.UserRef
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class LinearClientTest {

    // ── Fake GraphQL client ──────────────────────────────────────

    private class FakeGraphqlClient(
        private val responses: MutableList<JsonObject> = mutableListOf(),
        private val error: LinearError? = null
    ) : LinearGraphQLClient("http://localhost:1", "fake-key") {

        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun execute(query: String, variables: JsonObject): JsonObject {
            calls.add(query to variables)
            error?.let { throw it }
            return responses.removeFirst()
        }
    }

    private fun makeIssueNode(id: String, identifier: String, stateName: String) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("identifier", JsonPrimitive(identifier))
        put("title", JsonPrimitive("title-$id"))
        put("description", JsonNull)
        put("priority", JsonPrimitive(1))
        put("url", JsonNull)
        put("branchName", JsonNull)
        put("createdAt", JsonNull)
        put("updatedAt", JsonNull)
        put("state", buildJsonObject { put("name", JsonPrimitive(stateName)) })
        put("labels", buildJsonObject { put("nodes", buildJsonArray {}) })
        put("blockedBy", buildJsonObject { put("nodes", buildJsonArray {}) })
    }

    // ── IssueMapper ──────────────────────────────────────────────

    @Nested
    inner class IssueMapperTests {

        @Test
        fun `parses issue correctly with all fields`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
                put("identifier", JsonPrimitive("ABC-1"))
                put("title", JsonPrimitive("T1"))
                put("description", JsonPrimitive("desc"))
                put("priority", JsonPrimitive(2))
                put("url", JsonPrimitive("https://linear.app/abc-1"))
                put("branchName", JsonPrimitive("feat/test"))
                put("createdAt", JsonPrimitive("2024-01-15T10:30:00Z"))
                put("updatedAt", JsonPrimitive("2024-01-16T12:00:00Z"))
                put("state", buildJsonObject {
                    put("name", JsonPrimitive("In Progress"))
                })
                put("labels", buildJsonObject {
                    put("nodes", buildJsonArray {
                        add(buildJsonObject { put("name", JsonPrimitive("bug")) })
                        add(buildJsonObject { put("name", JsonPrimitive("urgent")) })
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
            assertThat(issue.description).isEqualTo("desc")
            assertThat(issue.priority).isEqualTo(2)
            assertThat(issue.url).isEqualTo("https://linear.app/abc-1")
            assertThat(issue.branchName).isEqualTo("feat/test")
            assertThat(issue.state).isEqualTo("In Progress")
            assertThat(issue.labels).isEqualTo(listOf("bug", "urgent"))
            assertThat(issue.blockedBy.isEmpty()).isTrue()
            assertThat(issue.createdAt).isEqualTo(Instant.parse("2024-01-15T10:30:00Z"))
            assertThat(issue.updatedAt).isEqualTo(Instant.parse("2024-01-16T12:00:00Z"))
        }

        @Test
        fun `parses issue with null optional fields`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-2"))
                put("identifier", JsonPrimitive("ABC-2"))
                put("title", JsonPrimitive("T2"))
                put("state", buildJsonObject {
                    put("name", JsonPrimitive("Todo"))
                })
                put("labels", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.description).isNull()
            assertThat(issue.priority).isNull()
            assertThat(issue.url).isNull()
            assertThat(issue.branchName).isNull()
            assertThat(issue.createdAt).isNull()
            assertThat(issue.updatedAt).isNull()
        }

        @Test
        fun `parses blocker refs`() {
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
        fun `parses multiple blockers`() {
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
                            put("id", JsonPrimitive("b1"))
                            put("identifier", JsonPrimitive("X-1"))
                            put("state", buildJsonObject {
                                put("name", JsonPrimitive("Done"))
                            })
                        })
                        add(buildJsonObject {
                            put("id", JsonPrimitive("b2"))
                            put("identifier", JsonPrimitive("X-2"))
                            put("state", buildJsonObject {
                                put("name", JsonPrimitive("In Progress"))
                            })
                        })
                    })
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.blockedBy.size).isEqualTo(2)
            assertThat(issue.blockedBy[0].id).isEqualTo("b1")
            assertThat(issue.blockedBy[1].id).isEqualTo("b2")
        }

        @Test
        fun `blocker with missing state yields null state`() {
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
                            put("id", JsonPrimitive("b1"))
                            put("identifier", JsonPrimitive("X-1"))
                        })
                    })
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.blockedBy[0].state).isNull()
        }

        @Test
        fun `missing labels node yields empty list`() {
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
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.labels.isEmpty()).isTrue()
        }

        @Test
        fun `missing blockedBy node yields empty list`() {
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
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.blockedBy.isEmpty()).isTrue()
        }

        @Test
        fun `labels with mixed case are lowercased and trimmed`() {
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
                    put("nodes", buildJsonArray {
                        add(buildJsonObject { put("name", JsonPrimitive("  Bug ")) })
                        add(buildJsonObject { put("name", JsonPrimitive("FEATURE")) })
                    })
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.labels).isEqualTo(listOf("bug", "feature"))
        }

        @Test
        fun `empty label names are filtered out`() {
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
                    put("nodes", buildJsonArray {
                        add(buildJsonObject { put("name", JsonPrimitive("")) })
                        add(buildJsonObject { put("name", JsonPrimitive("   ")) })
                        add(buildJsonObject { put("name", JsonPrimitive("valid")) })
                    })
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.labels).isEqualTo(listOf("valid"))
        }

        @Test
        fun `invalid createdAt string yields null`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
                put("identifier", JsonPrimitive("ABC-1"))
                put("title", JsonPrimitive("T1"))
                put("description", JsonNull)
                put("priority", JsonNull)
                put("url", JsonNull)
                put("branchName", JsonNull)
                put("createdAt", JsonPrimitive("not-a-date"))
                put("updatedAt", JsonNull)
                put("state", buildJsonObject {
                    put("name", JsonPrimitive("Todo"))
                })
                put("labels", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.createdAt).isNull()
        }

        @Test
        fun `invalid updatedAt string yields null`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
                put("identifier", JsonPrimitive("ABC-1"))
                put("title", JsonPrimitive("T1"))
                put("description", JsonNull)
                put("priority", JsonNull)
                put("url", JsonNull)
                put("branchName", JsonNull)
                put("createdAt", JsonNull)
                put("updatedAt", JsonPrimitive("garbage"))
                put("state", buildJsonObject {
                    put("name", JsonPrimitive("Todo"))
                })
                put("labels", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.updatedAt).isNull()
        }

        @Test
        fun `numeric priority is parsed`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
                put("identifier", JsonPrimitive("ABC-1"))
                put("title", JsonPrimitive("T1"))
                put("description", JsonNull)
                put("priority", JsonPrimitive(0))
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
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.priority).isEqualTo(0)
        }

        @Test
        fun `missing state node yields Unknown state`() {
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
                put("labels", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.state).isEqualTo("Unknown")
        }

        @Test
        fun `missing id field throws`() {
            val json = buildJsonObject {
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
                    put("nodes", buildJsonArray {})
                })
            }

            assertThrows<IllegalStateException> { IssueMapper.fromLinear(json) }
        }

        @Test
        fun `missing title field throws`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
                put("identifier", JsonPrimitive("ABC-1"))
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
                    put("nodes", buildJsonArray {})
                })
            }

            assertThrows<IllegalStateException> { IssueMapper.fromLinear(json) }
        }

        @Test
        fun `missing identifier field throws`() {
            val json = buildJsonObject {
                put("id", JsonPrimitive("id-1"))
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
                    put("nodes", buildJsonArray {})
                })
            }

            assertThrows<IllegalStateException> { IssueMapper.fromLinear(json) }
        }

        @Test
        fun `blockedBy with non-JsonObject node is skipped`() {
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
                        add(JsonPrimitive("not-an-object"))
                        add(buildJsonObject {
                            put("id", JsonPrimitive("b1"))
                            put("identifier", JsonPrimitive("X-1"))
                            put("state", buildJsonObject {
                                put("name", JsonPrimitive("Done"))
                            })
                        })
                    })
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.blockedBy.size).isEqualTo(1)
            assertThat(issue.blockedBy[0].id).isEqualTo("b1")
        }

        @Test
        fun `parses creator field`() {
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
                    put("nodes", buildJsonArray {})
                })
                put("creator", buildJsonObject {
                    put("id", JsonPrimitive("user-1"))
                    put("displayName", JsonPrimitive("Alice"))
                    put("isBot", JsonPrimitive(false))
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.creator).isEqualTo(UserRef("user-1", "Alice", false))
        }

        @Test
        fun `creator is null when missing`() {
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
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.creator).isNull()
        }

        @Test
        fun `parses bot creator`() {
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
                    put("nodes", buildJsonArray {})
                })
                put("creator", buildJsonObject {
                    put("id", JsonPrimitive("bot-1"))
                    put("displayName", JsonPrimitive("Linear Bot"))
                    put("isBot", JsonPrimitive(true))
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.creator).isEqualTo(UserRef("bot-1", "Linear Bot", true))
        }

        @Test
        fun `label with non-JsonObject node is skipped`() {
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
                    put("nodes", buildJsonArray {
                        add(JsonPrimitive("bad"))
                        add(buildJsonObject { put("name", JsonPrimitive("good")) })
                    })
                })
                put("blockedBy", buildJsonObject {
                    put("nodes", buildJsonArray {})
                })
            }

            val issue = IssueMapper.fromLinear(json)
            assertThat(issue.labels).isEqualTo(listOf("good"))
        }
    }

    // ── LinearError ──────────────────────────────────────────────

    @Nested
    inner class LinearErrorTests {

        @Test
        fun `MissingApiKey message`() {
            assertThat(LinearError.MissingApiKey().message).isEqualTo("missing_tracker_api_key")
        }

        @Test
        fun `MissingProjectSlug message`() {
            assertThat(LinearError.MissingProjectSlug().message).isEqualTo("missing_tracker_project_slug")
        }

        @Test
        fun `UnknownPayload message`() {
            assertThat(LinearError.UnknownPayload().message).isEqualTo("linear_unknown_payload")
        }

        @Test
        fun `MissingEndCursor message`() {
            assertThat(LinearError.MissingEndCursor().message).isEqualTo("linear_missing_end_cursor")
        }

        @Test
        fun `Status message includes code`() {
            assertThat(LinearError.Status(404).message).isEqualTo("linear_api_status: 404")
            assertThat(LinearError.Status(500).message).isEqualTo("linear_api_status: 500")
            assertThat(LinearError.Status(401).message).isEqualTo("linear_api_status: 401")
        }

        @Test
        fun `GraphQlErrors message includes detail`() {
            assertThat(LinearError.GraphQlErrors("err").message).isEqualTo("linear_graphql_errors: err")
            assertThat(LinearError.GraphQlErrors("field 'x' not found").message)
                .isEqualTo("linear_graphql_errors: field 'x' not found")
        }

        @Test
        fun `Request message includes detail`() {
            assertThat(LinearError.Request("fail").message).isEqualTo("linear_api_request: fail")
            assertThat(LinearError.Request("timeout").message).isEqualTo("linear_api_request: timeout")
        }

        @Test
        fun `Request preserves cause`() {
            val cause = RuntimeException("io error")
            val error = LinearError.Request("fail", cause)
            assertThat(error.cause).isEqualTo(cause)
            assertThat(error.message).isEqualTo("linear_api_request: fail")
        }

        @Test
        fun `all error types are instances of LinearError`() {
            val errors = listOf(
                LinearError.MissingApiKey(),
                LinearError.MissingProjectSlug(),
                LinearError.UnknownPayload(),
                LinearError.MissingEndCursor(),
                LinearError.Status(400),
                LinearError.GraphQlErrors("x"),
                LinearError.Request("x")
            )
            errors.forEach { assertThat(it is LinearError).isTrue() }
        }

        @Test
        fun `all error types are instances of Exception`() {
            val errors = listOf(
                LinearError.MissingApiKey(),
                LinearError.Request("x")
            )
            errors.forEach { assertThat(it is Exception).isTrue() }
        }
    }

    // ── DefaultLinearClient query strings ─────────────────────────

    @Nested
    inner class DefaultLinearClientQueryTests {

        private val dummyClient = LinearGraphQLClient("http://localhost:1", "dummy-key")
        private val sut = DefaultLinearClient(dummyClient, "test-project")

        @Test
        fun `candidateQuery contains project filter`() {
            assertThat(sut.candidateQuery).contains("project")
            assertThat(sut.candidateQuery).contains("slugId")
        }

        @Test
        fun `candidateQuery contains state filter`() {
            assertThat(sut.candidateQuery).contains("state")
            assertThat(sut.candidateQuery).contains("name")
        }

        @Test
        fun `candidateQuery contains pagination fields`() {
            assertThat(sut.candidateQuery).contains("hasNextPage")
            assertThat(sut.candidateQuery).contains("endCursor")
        }

        @Test
        fun `candidateQuery contains issue fields`() {
            assertThat(sut.candidateQuery).contains("identifier")
            assertThat(sut.candidateQuery).contains("title")
            assertThat(sut.candidateQuery).contains("description")
            assertThat(sut.candidateQuery).contains("priority")
            assertThat(sut.candidateQuery).contains("branchName")
        }

        @Test
        fun `candidateQuery contains blockedBy relation`() {
            assertThat(sut.candidateQuery).contains("blockedBy")
            assertThat(sut.candidateQuery).contains("blocks")
        }

        @Test
        fun `candidateQuery contains labels`() {
            assertThat(sut.candidateQuery).contains("labels")
        }

        @Test
        fun `issuesByStatesQuery contains project filter`() {
            assertThat(sut.issuesByStatesQuery).contains("project")
            assertThat(sut.issuesByStatesQuery).contains("slugId")
        }

        @Test
        fun `issuesByStatesQuery contains state filter`() {
            assertThat(sut.issuesByStatesQuery).contains("state")
            assertThat(sut.issuesByStatesQuery).contains("name")
        }

        @Test
        fun `issuesByStatesQuery contains issue fields`() {
            assertThat(sut.issuesByStatesQuery).contains("identifier")
            assertThat(sut.issuesByStatesQuery).contains("title")
            assertThat(sut.issuesByStatesQuery).contains("branchName")
        }

        @Test
        fun `statesByIdsQuery uses nodes query`() {
            assertThat(sut.statesByIdsQuery).contains("nodes")
            assertThat(sut.statesByIdsQuery).contains("filter")
        }

        @Test
        fun `statesByIdsQuery contains id and state fields`() {
            assertThat(sut.statesByIdsQuery).contains("id")
            assertThat(sut.statesByIdsQuery).contains("state")
            assertThat(sut.statesByIdsQuery).contains("name")
        }

        @Test
        fun `candidateQuery has variable declarations`() {
            assertThat(sut.candidateQuery).contains("\$projectSlug")
            assertThat(sut.candidateQuery).contains("\$states")
            assertThat(sut.candidateQuery).contains("\$first")
            assertThat(sut.candidateQuery).contains("\$after")
        }

        @Test
        fun `issuesByStatesQuery has variable declarations`() {
            assertThat(sut.issuesByStatesQuery).contains("\$projectSlug")
            assertThat(sut.issuesByStatesQuery).contains("\$states")
            assertThat(sut.issuesByStatesQuery).contains("\$first")
        }

        @Test
        fun `statesByIdsQuery has variable declarations`() {
            assertThat(sut.statesByIdsQuery).contains("\$ids")
        }

        @Test
        fun `issueByIdQuery contains creator field`() {
            assertThat(sut.issueByIdQuery).contains("creator")
            assertThat(sut.issueByIdQuery).contains("displayName")
            assertThat(sut.issueByIdQuery).contains("isBot")
        }

        @Test
        fun `createCommentMutation contains required fields`() {
            assertThat(sut.createCommentMutation).contains("commentCreate")
            assertThat(sut.createCommentMutation).contains("\$issueId")
            assertThat(sut.createCommentMutation).contains("\$body")
        }

        @Test
        fun `updateIssueAssigneeMutation contains required fields`() {
            assertThat(sut.updateIssueAssigneeMutation).contains("issueUpdate")
            assertThat(sut.updateIssueAssigneeMutation).contains("\$assigneeId")
        }
    }

    // ── DefaultLinearClient early returns ─────────────────────────

    @Nested
    inner class DefaultLinearClientEarlyReturnTests {

        private val dummyClient = LinearGraphQLClient("http://localhost:1", "dummy-key")
        private val sut = DefaultLinearClient(dummyClient, "test-project")

        @Test
        fun `fetchCandidateIssues returns empty list for empty states`() = runTest {
            val result = sut.fetchCandidateIssues("proj", emptyList())
            assertThat(result.isEmpty()).isTrue()
        }

        @Test
        fun `fetchIssuesByStates returns empty list for empty states`() = runTest {
            val result = sut.fetchIssuesByStates("proj", emptyList())
            assertThat(result.isEmpty()).isTrue()
        }

        @Test
        fun `fetchIssueStatesByIds returns empty map for empty ids`() = runTest {
            val result = sut.fetchIssueStatesByIds(emptyList())
            assertThat(result.isEmpty()).isTrue()
        }
    }

    // ── LinearGraphQLClient ───────────────────────────────────────

    @Nested
    inner class LinearGraphQLClientTests {

        @Test
        fun `execute throws MissingApiKey when apiKey is null`() = runTest {
            val client = LinearGraphQLClient("http://localhost:1", null)
            assertThrows<LinearError.MissingApiKey> {
                client.execute("query {}", buildJsonObject {})
            }
        }

        @Test
        fun `execute throws MissingApiKey when apiKey is blank`() = runTest {
            val client = LinearGraphQLClient("http://localhost:1", "  ")
            assertThrows<LinearError.MissingApiKey> {
                client.execute("query {}", buildJsonObject {})
            }
        }
    }

    // ── DefaultLinearClient fetch methods (fake) ─────────────────

    @Nested
    inner class DefaultLinearClientFetchTests {

        @Test
        fun `fetchCandidateIssues returns issues from single page`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("issues", buildJsonObject {
                                put("pageInfo", buildJsonObject {
                                    put("hasNextPage", JsonPrimitive(false))
                                    put("endCursor", JsonPrimitive("cursor-1"))
                                })
                                put("nodes", buildJsonArray {
                                    add(makeIssueNode("i1", "P-1", "Todo"))
                                    add(makeIssueNode("i2", "P-2", "Done"))
                                })
                            })
                        })
                    },
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("issues", buildJsonObject {
                                put("pageInfo", buildJsonObject {
                                    put("hasNextPage", JsonPrimitive(false))
                                })
                                put("nodes", buildJsonArray {})
                            })
                        })
                    }
                )
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchCandidateIssues("proj", listOf("Todo", "Done"))

            assertThat(result.size).isEqualTo(2)
            assertThat(result[0].id).isEqualTo("i1")
            assertThat(result[1].id).isEqualTo("i2")
        }

        @Test
        fun `fetchCandidateIssues paginates through multiple pages`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("issues", buildJsonObject {
                                put("pageInfo", buildJsonObject {
                                    put("hasNextPage", JsonPrimitive(true))
                                    put("endCursor", JsonPrimitive("cursor-1"))
                                })
                                put("nodes", buildJsonArray {
                                    add(makeIssueNode("i1", "P-1", "Todo"))
                                })
                            })
                        })
                    },
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("issues", buildJsonObject {
                                put("pageInfo", buildJsonObject {
                                    put("hasNextPage", JsonPrimitive(false))
                                    put("endCursor", JsonPrimitive("cursor-2"))
                                })
                                put("nodes", buildJsonArray {
                                    add(makeIssueNode("i2", "P-2", "Done"))
                                })
                            })
                        })
                    },
                    buildJsonObject {
                        put("data", buildJsonObject {
                            put("issues", buildJsonObject {
                                put("pageInfo", buildJsonObject {
                                    put("hasNextPage", JsonPrimitive(false))
                                })
                                put("nodes", buildJsonArray {})
                            })
                        })
                    }
                )
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchCandidateIssues("proj", listOf("Todo"))

            assertThat(result.size).isEqualTo(2)
            assertThat(result[0].id).isEqualTo("i1")
            assertThat(result[1].id).isEqualTo("i2")
            assertThat(fake.calls.size).isEqualTo(3)
        }

        @Test
        fun `fetchCandidateIssues throws MissingEndCursor when hasNextPage true but endCursor null`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("pageInfo", buildJsonObject {
                                put("hasNextPage", JsonPrimitive(true))
                            })
                            put("nodes", buildJsonArray {})
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.MissingEndCursor> {
                sut.fetchCandidateIssues("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchCandidateIssues throws UnknownPayload when data is missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("errors", buildJsonArray { add(JsonPrimitive("err")) })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchCandidateIssues("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchCandidateIssues throws UnknownPayload when issues key missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchCandidateIssues("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchCandidateIssues throws UnknownPayload when nodes is missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("pageInfo", buildJsonObject {
                                put("hasNextPage", JsonPrimitive(false))
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchCandidateIssues("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchCandidateIssues throws UnknownPayload when pageInfo missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("nodes", buildJsonArray {})
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchCandidateIssues("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchIssuesByStates returns mapped issues`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("pageInfo", buildJsonObject {
                                put("hasNextPage", JsonPrimitive(false))
                            })
                            put("nodes", buildJsonArray {
                                add(makeIssueNode("i1", "P-1", "Active"))
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchIssuesByStates("proj", listOf("Active"))

            assertThat(result.size).isEqualTo(1)
            assertThat(result[0].id).isEqualTo("i1")
            assertThat(result[0].state).isEqualTo("Active")
        }

        @Test
        fun `fetchIssuesByStates throws UnknownPayload when data missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchIssuesByStates("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchIssuesByStates throws UnknownPayload when issues key missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchIssuesByStates("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchIssuesByStates throws UnknownPayload when nodes missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {})
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchIssuesByStates("proj", listOf("Todo"))
            }
        }

        @Test
        fun `fetchIssueStatesByIds returns id to state map`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("nodes", buildJsonArray {
                            add(buildJsonObject {
                                put("id", JsonPrimitive("i1"))
                                put("state", buildJsonObject {
                                    put("name", JsonPrimitive("Done"))
                                })
                            })
                            add(buildJsonObject {
                                put("id", JsonPrimitive("i2"))
                                put("state", buildJsonObject {
                                    put("name", JsonPrimitive("Todo"))
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchIssueStatesByIds(listOf("i1", "i2"))

            assertThat(result.size).isEqualTo(2)
            assertThat(result["i1"]).isEqualTo("Done")
            assertThat(result["i2"]).isEqualTo("Todo")
        }

        @Test
        fun `fetchIssueStatesByIds skips nodes with null state`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("nodes", buildJsonArray {
                            add(buildJsonObject {
                                put("id", JsonPrimitive("i1"))
                                put("state", buildJsonObject {
                                    put("name", JsonPrimitive("Done"))
                                })
                            })
                            add(buildJsonObject {
                                put("id", JsonPrimitive("i2"))
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchIssueStatesByIds(listOf("i1", "i2"))

            assertThat(result.size).isEqualTo(1)
            assertThat(result["i1"]).isEqualTo("Done")
        }

        @Test
        fun `fetchIssueStatesByIds uses empty string for null id`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("nodes", buildJsonArray {
                            add(buildJsonObject {
                                put("state", buildJsonObject {
                                    put("name", JsonPrimitive("Done"))
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.fetchIssueStatesByIds(listOf("i1"))

            assertThat(result.size).isEqualTo(1)
            assertThat(result[""]).isEqualTo("Done")
        }

        @Test
        fun `fetchIssueStatesByIds throws UnknownPayload when nodes missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            assertThrows<LinearError.UnknownPayload> {
                sut.fetchIssueStatesByIds(listOf("i1"))
            }
        }

        @Test
        fun `fetchCandidateIssues passes correct variables`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("pageInfo", buildJsonObject {
                                put("hasNextPage", JsonPrimitive(false))
                            })
                            put("nodes", buildJsonArray {})
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "my-proj")
            sut.fetchCandidateIssues("my-proj", listOf("Todo", "In Progress"))

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("Candidates")
            assertThat(vars["projectSlug"] == JsonPrimitive("my-proj")).isTrue()
            assertThat(vars["first"] == JsonPrimitive(50)).isTrue()
        }

        @Test
        fun `fetchIssuesByStates passes correct variables`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issues", buildJsonObject {
                            put("pageInfo", buildJsonObject {
                                put("hasNextPage", JsonPrimitive(false))
                            })
                            put("nodes", buildJsonArray {})
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "my-proj")
            sut.fetchIssuesByStates("my-proj", listOf("Done"))

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("IssuesByStates")
            assertThat(vars["projectSlug"] == JsonPrimitive("my-proj")).isTrue()
            assertThat(vars["first"] == JsonPrimitive(50)).isTrue()
        }

        @Test
        fun `fetchIssueStatesByIds passes correct variables`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("nodes", buildJsonArray {})
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            sut.fetchIssueStatesByIds(listOf("a", "b"))

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("StatesByIds")
            val ids = vars["ids"] as? kotlinx.serialization.json.JsonArray
            assertThat(ids != null && ids.size == 2).isTrue()
        }

        @Test
        fun `createComment sends correct mutation`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("commentCreate", buildJsonObject {
                            put("success", JsonPrimitive(true))
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            sut.createComment("issue-1", "Hello from agent")

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("CommentCreate")
            assertThat(vars["issueId"]).isEqualTo(JsonPrimitive("issue-1"))
            assertThat(vars["body"]).isEqualTo(JsonPrimitive("Hello from agent"))
        }

        @Test
        fun `updateIssueAssignee sends correct mutation`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issueUpdate", buildJsonObject {
                            put("success", JsonPrimitive(true))
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            sut.updateIssueAssignee("issue-1", "user-42")

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("IssueAssigneeUpdate")
            assertThat(vars["id"]).isEqualTo(JsonPrimitive("issue-1"))
            assertThat(vars["assigneeId"]).isEqualTo(JsonPrimitive("user-42"))
        }

        @Test
        fun `resolveStateId returns state id when found`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("Todo"))
                                        })
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-2"))
                                            put("name", JsonPrimitive("Done"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Done")
            assertThat(result).isEqualTo("state-2")
        }

        @Test
        fun `resolveStateId matches case-insensitively`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("todo"))
                                        })
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-2"))
                                            put("name", JsonPrimitive("In Progress"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Todo")
            assertThat(result).isEqualTo("state-1")
        }

        @Test
        fun `resolveStateId returns null when project missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Done")
            assertThat(result).isNull()
        }

        @Test
        fun `resolveStateId returns null when team missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {})
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Done")
            assertThat(result).isNull()
        }

        @Test
        fun `resolveStateId returns null when states missing`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {})
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Done")
            assertThat(result).isNull()
        }

        @Test
        fun `resolveStateId returns null when state not found`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("Todo"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Blocked")
            assertThat(result).isNull()
        }

        @Test
        fun `resolveStateId skips non-JsonObject nodes`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(JsonPrimitive("not-an-object"))
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("Todo"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Todo")
            assertThat(result).isEqualTo("state-1")
        }

        @Test
        fun `resolveStateId skips nodes without name`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-0"))
                                        })
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("Todo"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.resolveStateId("proj", "Todo")
            assertThat(result).isEqualTo("state-1")
        }

        @Test
        fun `updateIssueState sends correct mutation`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            sut.updateIssueState("issue-1", "state-2")

            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("IssueUpdate")
            assertThat(vars["id"]).isEqualTo(JsonPrimitive("issue-1"))
            assertThat(vars["stateId"]).isEqualTo(JsonPrimitive("state-2"))
        }

        @Test
        fun `fetchIssueById returns issue when found`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issue", buildJsonObject {
                            put("id", JsonPrimitive("issue-1"))
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
                                put("nodes", buildJsonArray {})
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val issue = sut.fetchIssueById("issue-1")
            assertThat(issue).isNotNull()
            assertThat(issue!!.id).isEqualTo("issue-1")
            assertThat(issue.identifier).isEqualTo("ABC-1")
        }

        @Test
        fun `fetchIssueById returns null when issue not found`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val issue = sut.fetchIssueById("issue-unknown")
            assertThat(issue).isNull()
        }

        @Test
        fun `fetchIssueCreator returns creator when issue exists`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {
                        put("issue", buildJsonObject {
                            put("id", JsonPrimitive("issue-1"))
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
                            put("creator", buildJsonObject {
                                put("id", JsonPrimitive("user-1"))
                                put("displayName", JsonPrimitive("Alice"))
                                put("isBot", JsonPrimitive(false))
                            })
                            put("blockedBy", buildJsonObject {
                                put("nodes", buildJsonArray {})
                            })
                        })
                    })
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val creator = sut.fetchIssueCreator("issue-1")

            assertThat(creator).isEqualTo(UserRef("user-1", "Alice", false))
        }

        @Test
        fun `fetchIssueCreator returns null when issue not found`() = runTest {
            val fake = FakeGraphqlClient(
                responses = mutableListOf(buildJsonObject {
                    put("data", buildJsonObject {})
                })
            )

            val sut = DefaultLinearClient(fake, "proj")
            val creator = sut.fetchIssueCreator("issue-unknown")

            assertThat(creator).isNull()
        }
    }

    // ── DefaultLinearClient createIssue ──────────────────────────

    @Nested
    inner class DefaultLinearClientCreateIssueTests {

        private fun createClient(
            responses: MutableList<JsonObject> = mutableListOf(),
            error: LinearError? = null
        ) = DefaultLinearClient(
            graphql = FakeGraphqlClient(responses, error),
            projectSlug = "test-project"
        )

        @Test
        fun `createIssue returns issue on success`() = runTest {
            val responses = mutableListOf(
                buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("id", JsonPrimitive("team-1"))
                            })
                        })
                    })
                },
                buildJsonObject {
                    put("data", buildJsonObject {
                        put("project", buildJsonObject {
                            put("team", buildJsonObject {
                                put("states", buildJsonObject {
                                    put("nodes", buildJsonArray {
                                        add(buildJsonObject {
                                            put("id", JsonPrimitive("state-1"))
                                            put("name", JsonPrimitive("Todo"))
                                        })
                                    })
                                })
                            })
                        })
                    })
                },
                buildJsonObject {
                    put("data", buildJsonObject {
                        put("issueCreate", buildJsonObject {
                            put("success", JsonPrimitive(true))
                            put("issue", buildJsonObject {
                                put("id", JsonPrimitive("new-1"))
                                put("identifier", JsonPrimitive("ENG-100"))
                                put("title", JsonPrimitive("Test Issue"))
                                put("state", buildJsonObject { put("name", JsonPrimitive("Todo")) })
                            })
                        })
                    })
                }
            )
            val client = createClient(responses = responses)
            val result = client.createIssue("test-project", "Test Issue", "Todo")
            assertThat(result).isNotNull()
            assertThat(result!!.title).isEqualTo("Test Issue")
            assertThat(result.identifier).isEqualTo("ENG-100")
        }

        @Test
        fun `createIssue returns null on API error`() = runTest {
            val client = createClient(error = LinearError.UnknownPayload())
            val result = client.createIssue("test-project", "Test Issue", "Todo")
            assertThat(result).isNull()
        }

        @Test
        fun `createIssue fails when team not resolved`() = runTest {
            val responses = mutableListOf(buildJsonObject {
                put("data", buildJsonObject {})
            })
            val client = createClient(responses = responses)
            val result = client.createIssue("test-project", "Test Issue", "Todo")
            assertThat(result).isNull()
        }
    }

    // ── DefaultLinearClient createLink ───────────────────────────

    @Nested
    inner class DefaultLinearClientCreateLinkTests {

        @Test
        fun `createLink returns true on success`() = runTest {
            val responses = mutableListOf(buildJsonObject {
                put("data", buildJsonObject {
                    put("issueRelationCreate", buildJsonObject {
                        put("success", JsonPrimitive(true))
                    })
                })
            })
            val fake = FakeGraphqlClient(responses = responses)
            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.createLink("src-1", "tgt-1", "blocks")
            assertThat(result).isTrue()
        }

        @Test
        fun `createLink returns false on API error`() = runTest {
            val fake = FakeGraphqlClient(error = LinearError.UnknownPayload())
            val sut = DefaultLinearClient(fake, "proj")
            val result = sut.createLink("src-1", "tgt-1", "blocks")
            assertThat(result).isFalse()
        }

        @Test
        fun `createLink sends correct mutation`() = runTest {
            val responses = mutableListOf(buildJsonObject {
                put("data", buildJsonObject {
                    put("issueRelationCreate", buildJsonObject {
                        put("success", JsonPrimitive(true))
                    })
                })
            })
            val fake = FakeGraphqlClient(responses = responses)
            val sut = DefaultLinearClient(fake, "proj")
            sut.createLink("src-1", "tgt-1", "blocks")
            assertThat(fake.calls.size).isEqualTo(1)
            val (query, vars) = fake.calls[0]
            assertThat(query).contains("RelationCreate")
            assertThat(vars["issueId"]).isEqualTo(JsonPrimitive("src-1"))
            assertThat(vars["relatedIssueId"]).isEqualTo(JsonPrimitive("tgt-1"))
            assertThat(vars["type"]).isEqualTo(JsonPrimitive("blocks"))
        }
    }
}
