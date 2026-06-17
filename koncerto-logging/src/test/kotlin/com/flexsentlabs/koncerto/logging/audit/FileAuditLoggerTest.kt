package com.flexsentlabs.koncerto.logging.audit

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.audit.AuditEvent
import com.flexsentlabs.koncerto.core.audit.AuditEventType
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class FileAuditLoggerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `creates parent directories on init`() {
        val nested = tempDir.resolve("sub/dir/audit.log")
        val logger = FileAuditLogger(nested)
        assertThat(Files.exists(nested.parent)).isTrue()
        logger.close()
    }

    @Test
    fun `writes audit event as JSON line`() {
        val file = tempDir.resolve("events.jsonl")
        val logger = FileAuditLogger(file)
        val event = AuditEvent(
            timestamp = 1000L,
            type = AuditEventType.AGENT_DISPATCHED,
            projectSlug = "proj1",
            issueId = "issue-1"
        )
        logger.log(event)
        logger.close()
        val line = Files.readString(file).trim()
        val parsed = Json.decodeFromString<AuditEvent>(line)
        assertThat(parsed.timestamp).isEqualTo(1000L)
        assertThat(parsed.type).isEqualTo(AuditEventType.AGENT_DISPATCHED)
        assertThat(parsed.projectSlug).isEqualTo("proj1")
    }

    @Test
    fun `appends to existing file`() {
        val file = tempDir.resolve("append.jsonl")
        val logger = FileAuditLogger(file)
        logger.log(AuditEvent(1000L, AuditEventType.AGENT_DISPATCHED, "p1", "i1"))
        logger.close()
        val logger2 = FileAuditLogger(file)
        logger2.log(AuditEvent(2000L, AuditEventType.AGENT_COMPLETED, "p1", "i1"))
        logger2.close()
        val lines = Files.readAllLines(file)
        assertThat(lines.size).isEqualTo(2)
    }

    @Test
    fun `close does not throw`() {
        val file = tempDir.resolve("close.jsonl")
        val logger = FileAuditLogger(file)
        logger.log(AuditEvent(1000L, AuditEventType.AGENT_DISPATCHED, "p", "i"))
        logger.close()
    }
}
