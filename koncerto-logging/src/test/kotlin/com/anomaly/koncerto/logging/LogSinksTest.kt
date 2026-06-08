package com.anomaly.koncerto.logging

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.contains
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LogSinksTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `StderrSink writes without throwing`() {
        val sink = StderrSink()
        sink.write("hello stderr")
    }

    @Test
    fun `FileSink writes lines to file`() {
        val file = tempDir.resolve("test.log")
        val sink = FileSink(file)
        sink.write("line one")
        sink.write("line two")
        sink.write("line three")
        val lines = file.toFile().readLines()
        assertThat(lines.size).isEqualTo(3)
        assertThat(lines[0]).isEqualTo("line one")
        assertThat(lines[1]).isEqualTo("line two")
        assertThat(lines[2]).isEqualTo("line three")
    }

    @Test
    fun `FileSink handles multiple writes to same file`() {
        val file = tempDir.resolve("multi.log")
        val sink = FileSink(file)
        repeat(5) { sink.write("entry $it") }
        val lines = file.toFile().readLines()
        assertThat(lines.size).isEqualTo(5)
        assertThat(lines[4]).isEqualTo("entry 4")
    }

    @Test
    fun `CompositeSink writes to all sinks`() {
        val a = StringListSink()
        val b = StringListSink()
        val composite = CompositeSink(listOf(a, b))
        composite.write("broadcast")
        assertThat(a.lines.single()).isEqualTo("broadcast")
        assertThat(b.lines.single()).isEqualTo("broadcast")
    }

    @Test
    fun `CompositeSink continues when a sink throws`() {
        val good = StringListSink()
        val bad = object : LogSink {
            override fun write(line: String) { throw RuntimeException("boom") }
        }
        val composite = CompositeSink(listOf(bad, good))
        composite.write("survived")
        assertThat(good.lines.single()).isEqualTo("survived")
    }

    @Test
    fun `CompositeSink continues when middle sink throws`() {
        val first = StringListSink()
        val bad = object : LogSink {
            override fun write(line: String) { throw RuntimeException("boom") }
        }
        val last = StringListSink()
        val composite = CompositeSink(listOf(first, bad, last))
        composite.write("partial")
        assertThat(first.lines.single()).isEqualTo("partial")
        assertThat(last.lines.single()).isEqualTo("partial")
    }

    @Test
    fun `CompositeSink with empty list does not throw`() {
        val composite = CompositeSink(emptyList())
        composite.write("no-op")
    }
}
