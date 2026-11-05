package com.flexsentlabs.koncerto.orchestrator

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64

/** Durable, per-project pending tracker transitions. Callers must serialize access. */
internal class ReviewBlockJournal(private val directory: Path) {
    data class Entry(val issueId: String, val originState: String, val sent: Boolean)

    init {
        require(directory.isAbsolute && directory.normalize() == directory && directory != directory.root)
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory) && directory.toRealPath() == directory)
    }

    private fun key(issueId: String): String {
        require(issueId.isNotEmpty() && issueId.length <= 1024)
        return MessageDigest.getInstance("SHA-256").digest(issueId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) } + ".gate"
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    fun put(entry: Entry) {
        require(entry.originState.isNotEmpty() && entry.originState.length <= 1024)
        val target = directory.resolve(key(entry.issueId))
        require(!Files.isSymbolicLink(target))
        val tmp = Files.createTempFile(directory, ".pending-", ".tmp")
        try {
            val text = "v1\n${encode(entry.issueId)}\n${encode(entry.originState)}\n${if (entry.sent) "sent" else "pending"}\n"
            FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    fun entries(): List<Entry> = Files.list(directory).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".gate") }.map { path ->
            require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            require(Files.size(path) <= 8192)
            val lines = Files.readAllLines(path, StandardCharsets.UTF_8)
            require(lines.size == 4 && lines[0] == "v1" && lines[3] in setOf("pending", "sent"))
            val entry = Entry(decode(lines[1]), decode(lines[2]), lines[3] == "sent")
            require(entry.originState.isNotEmpty() && entry.originState.length <= 1024)
            require(path.fileName.toString() == key(entry.issueId))
            entry
        }.toList()
    }

    fun remove(issueId: String) {
        val target = directory.resolve(key(issueId))
        require(!Files.isSymbolicLink(target))
        if (Files.deleteIfExists(target)) FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}
