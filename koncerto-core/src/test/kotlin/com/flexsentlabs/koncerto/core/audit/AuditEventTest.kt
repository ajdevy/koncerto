package com.flexsentlabs.koncerto.core.audit

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test

class AuditEventTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `round-trip serialization preserves all fields`() {
        val event = AuditEvent(
            timestamp = 1_700_000_000_000L,
            type = AuditEventType.AGENT_DISPATCHED,
            projectSlug = "koncerto",
            issueId = "issue-42",
            issueIdentifier = "KON-42",
            agentKind = "codex",
            inputTokens = 100L,
            outputTokens = 50L,
            totalTokens = 150L,
            error = null,
            attempt = 1
        )
        val encoded = json.encodeToString(event)
        val decoded = json.decodeFromString<AuditEvent>(encoded)

        assertThat(decoded.timestamp).isEqualTo(event.timestamp)
        assertThat(decoded.type).isEqualTo(AuditEventType.AGENT_DISPATCHED)
        assertThat(decoded.projectSlug).isEqualTo("koncerto")
        assertThat(decoded.issueId).isEqualTo("issue-42")
        assertThat(decoded.issueIdentifier).isEqualTo("KON-42")
        assertThat(decoded.agentKind).isEqualTo("codex")
        assertThat(decoded.inputTokens).isEqualTo(100L)
        assertThat(decoded.outputTokens).isEqualTo(50L)
        assertThat(decoded.totalTokens).isEqualTo(150L)
        assertThat(decoded.error).isNull()
        assertThat(decoded.attempt).isEqualTo(1)
    }

    @Test
    fun `round-trip serialization for all event types`() {
        AuditEventType.entries.forEach { type ->
            val event = AuditEvent(
                timestamp = 1L,
                type = type,
                projectSlug = "p",
                issueId = "id",
                error = if (type == AuditEventType.AGENT_FAILED) "boom" else null
            )
            val decoded = json.decodeFromString<AuditEvent>(json.encodeToString(event))
            assertThat(decoded.type).isEqualTo(type)
        }
    }

    @Test
    fun `round-trip serialization with optional fields omitted`() {
        val event = AuditEvent(
            timestamp = 99L,
            type = AuditEventType.AGENT_COMPLETED,
            projectSlug = "proj",
            issueId = "abc"
        )
        val decoded = json.decodeFromString<AuditEvent>(json.encodeToString(event))
        assertThat(decoded.issueIdentifier).isNull()
        assertThat(decoded.agentKind).isNull()
        assertThat(decoded.inputTokens).isNull()
        assertThat(decoded.outputTokens).isNull()
        assertThat(decoded.totalTokens).isNull()
        assertThat(decoded.error).isNull()
        assertThat(decoded.attempt).isNull()
    }
}
