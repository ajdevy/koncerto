package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ReviewBlockJournalTest {
    @Test
    fun `pending and sent phases survive restart and can be removed`(@TempDir root: Path) {
        val dir = root.resolve("gates")
        val pending = ReviewBlockJournal.Entry("issue-1", "In Review", false)
        ReviewBlockJournal(dir).put(pending)
        assertThat(ReviewBlockJournal(dir).entries()).isEqualTo(listOf(pending))
        val sent = pending.copy(sent = true)
        ReviewBlockJournal(dir).put(sent)
        assertThat(ReviewBlockJournal(dir).entries()).isEqualTo(listOf(sent))
        ReviewBlockJournal(dir).remove(sent.issueId)
        assertThat(ReviewBlockJournal(dir).entries()).isEmpty()
    }

    @Test
    fun `corrupt and symlink records fail closed`(@TempDir root: Path) {
        val journal = ReviewBlockJournal(root.resolve("gates"))
        journal.put(ReviewBlockJournal.Entry("issue-1", "In Review", false))
        val file = Files.list(root.resolve("gates")).use { it.findFirst().orElseThrow() }
        Files.writeString(file, "bad")
        assertThrows<IllegalArgumentException> { journal.entries() }
        Files.delete(file)
        Files.createSymbolicLink(file, root.resolve("outside"))
        assertThrows<IllegalArgumentException> { journal.entries() }
    }
}
