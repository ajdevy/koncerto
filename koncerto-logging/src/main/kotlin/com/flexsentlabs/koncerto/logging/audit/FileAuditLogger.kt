package com.flexsentlabs.koncerto.logging.audit

import com.flexsentlabs.koncerto.core.audit.AuditEvent
import com.flexsentlabs.koncerto.core.audit.AuditLogger
import kotlinx.serialization.json.Json
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class FileAuditLogger(
    filePath: Path,
    private val json: Json = Json { encodeDefaults = false }
) : AuditLogger, AutoCloseable {

    private val writer: BufferedWriter

    init {
        val parent = filePath.parent
        if (parent != null) Files.createDirectories(parent)
        writer = BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(filePath.toFile(), true),
                StandardCharsets.UTF_8
            )
        )
    }

    override fun log(event: AuditEvent) {
        val line = json.encodeToString(AuditEvent.serializer(), event)
        synchronized(this) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    override fun close() {
        writer.close()
    }
}
