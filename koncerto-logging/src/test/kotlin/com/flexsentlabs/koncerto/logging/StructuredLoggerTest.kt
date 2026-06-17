package com.flexsentlabs.koncerto.logging

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

    @Test
    fun `warn logs at warn level with warning outcome`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.warn("disk_space", mapOf("free_mb" to "100"))
        val output = sink.lines.single()
        assertThat(output).contains("level=warn")
        assertThat(output).contains("action=disk_space")
        assertThat(output).contains("outcome=warning")
    }

    @Test
    fun `error logs at error level with failed outcome`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.error("api_down", mapOf("endpoint" to "linear"))
        val output = sink.lines.single()
        assertThat(output).contains("level=error")
        assertThat(output).contains("outcome=failed")
    }

    @Test
    fun `debug logs at debug level with completed outcome`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.debug("poll", mapOf("count" to "5"))
        val output = sink.lines.single()
        assertThat(output).contains("level=debug")
        assertThat(output).contains("outcome=completed")
    }

    @Test
    fun `values with whitespace are quoted`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.info("test", mapOf("msg" to "hello world"))
        val output = sink.lines.single()
        assertThat(output).contains("msg=\"hello world\"")
    }

    @Test
    fun `values with quotes are escaped`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.info("test", mapOf("msg" to """say "hello""""))
        val output = sink.lines.single()
        assertThat(output).contains("""msg="say \"hello\"""")
    }

    @Test
    fun `sink that throws does not propagate`() {
        val bad = object : LogSink {
            override fun write(line: String) { throw RuntimeException("kaboom") }
        }
        val logger = StructuredLogger(listOf(bad))
        logger.info("test", emptyMap())
    }

    @Test
    fun `empty context and kvs still logs`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.info("ping", emptyMap())
        val output = sink.lines.single()
        assertThat(output).contains("action=ping")
        assertThat(output).contains("level=info")
        assertThat(output).contains("outcome=completed")
    }

    @Test
    fun `failure with null message logs class name`() {
        val sink = StringListSink()
        val logger = StructuredLogger(listOf(sink))
        logger.failure("crash", emptyMap(), NullPointerException())
        val output = sink.lines.single()
        assertThat(output).contains("error=NullPointerException")
    }
}

class StringListSink : LogSink {
    val lines = mutableListOf<String>()
    override fun write(line: String) { lines.add(line) }
}
