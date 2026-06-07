package com.anomaly.koncerto.logging

interface LogSink {
    fun write(line: String)
}

class StderrSink : LogSink {
    override fun write(line: String) {
        System.err.println(line)
    }
}

class FileSink(path: java.nio.file.Path) : LogSink {
    private val writer = path.toFile().bufferedWriter()
    override fun write(line: String) {
        synchronized(writer) { writer.write(line); writer.newLine(); writer.flush() }
    }
}

class CompositeSink(private val sinks: List<LogSink>) : LogSink {
    override fun write(line: String) {
        sinks.forEach {
            try { it.write(line) } catch (_: Throwable) { /* keep going */ }
        }
    }
}
