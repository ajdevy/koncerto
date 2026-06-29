package com.flexsentlabs.koncerto.logging

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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

class RollingFileSink(
    private val directory: Path,
    private val baseName: String = "koncerto",
    private val retentionDays: Long = 7,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) : LogSink {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private var currentDate: LocalDate? = null
    private var writer: java.io.BufferedWriter? = null

    init {
        Files.createDirectories(directory)
        pruneOldFiles()
    }

    override fun write(line: String) {
        synchronized(this) {
            val today = LocalDate.now(zoneId)
            if (currentDate != today) {
                writer?.close()
                currentDate = today
                val path = directory.resolve("$baseName-${dateFormatter.format(today)}.log")
                writer = path.toFile().bufferedWriter().also {
                    pruneOldFiles()
                }
            }
            val w = writer ?: return
            w.write(line)
            w.newLine()
            w.flush()
        }
    }

    private fun pruneOldFiles() {
        if (retentionDays <= 0) return
        val cutoff = LocalDate.now(zoneId).minusDays(retentionDays)
        try {
            val stream = Files.list(directory)
            try {
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$baseName-") && name.endsWith(".log")
                }.forEach { path ->
                    val name = path.fileName.toString()
                    val datePart = name.removePrefix("$baseName-").removeSuffix(".log")
                    val fileDate = runCatching { LocalDate.parse(datePart, dateFormatter) }.getOrNull() ?: return@forEach
                    if (fileDate.isBefore(cutoff)) {
                        runCatching { Files.deleteIfExists(path) }
                    }
                }
            } finally {
                stream.close()
            }
        } catch (_: Exception) {
            // Best-effort retention only.
        }
    }
}

class CompositeSink(private val sinks: List<LogSink>) : LogSink {
    override fun write(line: String) {
        sinks.forEach {
            try { it.write(line) } catch (_: Throwable) { /* keep going */ }
        }
    }
}
