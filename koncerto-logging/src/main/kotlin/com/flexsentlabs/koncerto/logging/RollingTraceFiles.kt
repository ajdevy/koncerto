package com.flexsentlabs.koncerto.logging

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

object RollingTraceFiles {
    private val dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val locks = ConcurrentHashMap<String, Any>()

    fun append(
        directory: Path,
        baseName: String,
        line: String,
        retentionDays: Long = 7,
        zoneId: ZoneId = ZoneId.systemDefault()
    ) {
        val lockKey = "${directory.toAbsolutePath().normalize()}|$baseName|$retentionDays|${zoneId.id}"
        val lock = locks.computeIfAbsent(lockKey) { Any() }
        synchronized(lock) {
            Files.createDirectories(directory)
            pruneOldFiles(directory, baseName, retentionDays, zoneId)
            val today = LocalDate.now(zoneId)
            val traceFile = directory.resolve("$baseName-${dateFormatter.format(today)}.jsonl")
            Files.writeString(
                traceFile,
                line + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
        }
    }

    private fun pruneOldFiles(
        directory: Path,
        baseName: String,
        retentionDays: Long,
        zoneId: ZoneId
    ) {
        if (retentionDays <= 0) return
        val cutoff = LocalDate.now(zoneId).minusDays(retentionDays)
        try {
            val stream = Files.list(directory)
            try {
                stream.filter { path ->
                    val name = path.fileName.toString()
                    name.startsWith("$baseName-") && name.endsWith(".jsonl")
                }.forEach { path ->
                    val name = path.fileName.toString()
                    val datePart = name.removePrefix("$baseName-").removeSuffix(".jsonl")
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
