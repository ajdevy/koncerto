package com.anomaly.koncerto.logging

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class StructuredLoggerTest {

    @Test
    fun `structured log includes key=value pairs and context`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.info(
            "dispatch_completed",
            mapOf("issue_id" to "i1", "issue_identifier" to "ABC-1"),
            "outcome" to "success"
        )
        val output = sink.lines.single()
        assertThat(output).contains("action=dispatch_completed")
        assertThat(output).contains("issue_id=i1")
        assertThat(output).contains("issue_identifier=ABC-1")
        assertThat(output).contains("outcome=success")
        assertThat(output).contains("level=info")
    }

    @Test
    fun `failures are logged with error key`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.failure("turn_failed", mapOf("issue_id" to "i2"), RuntimeException("boom"), "attempt" to "1")
        val output = sink.lines.single()
        assertThat(output).contains("action=turn_failed")
        assertThat(output).contains("outcome=failed")
        assertThat(output).contains("error=boom")
        assertThat(output).contains("attempt=1")
    }

    @Test
    fun `multi sinks are all written`() {
        val a = StringListSink()
        val b = StringListSink()
        val logger = StructuredLogger(listOf(a, b))
        logger.info("tick", emptyMap())
        assertThat(a.lines.size).isEqualTo(1)
        assertThat(b.lines.size).isEqualTo(1)
    }
}

class StringListSink : LogSink {
    val lines = mutableListOf<String>()
    override fun write(line: String) { lines.add(line) }
}
