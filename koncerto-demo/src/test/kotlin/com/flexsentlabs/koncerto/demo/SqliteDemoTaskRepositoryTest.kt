package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.repository.SqliteDemoTaskRepository
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SqliteDemoTaskRepositoryTest {

    private val repo = SqliteDemoTaskRepository(":memory:")

    @AfterEach
    fun afterEach() {}

    @Test
    fun `save and findById roundtrip`() = runTest {
        val task = createTask()
        repo.save(task)
        val found = repo.findById(task.id)
        assert(found != null)
        assert(found!!.id == task.id)
        assert(found.platform == task.platform)
        assert(found.status == DemoStatus.PENDING)
    }

    @Test
    fun `findById returns null for missing task`() = runTest {
        assert(repo.findById("nonexistent") == null)
    }

    @Test
    fun `findByIssue returns all tasks for issue`() = runTest {
        repo.save(createTask(issueId = "issue-1"))
        repo.save(createTask(issueId = "issue-1"))
        repo.save(createTask(issueId = "issue-2"))
        assert(repo.findByIssue("issue-1").size == 2)
    }

    @Test
    fun `updateStatus changes status`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateStatus(task.id, DemoStatus.COMPLETED)
        val updated = repo.findById(task.id)!!
        assert(updated.status == DemoStatus.COMPLETED)
        assert(updated.completedAt != null)
    }

    @Test
    fun `updateStatus with error message`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateStatus(task.id, DemoStatus.FAILED, "something went wrong")
        val updated = repo.findById(task.id)!!
        assert(updated.status == DemoStatus.FAILED)
        assert(updated.errorMessage == "something went wrong")
    }

    @Test
    fun `updateCompleted sets recording metadata`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateCompleted(task.id, DemoStatus.COMPLETED, "https://example.com/video.webm", "demo-recordings/key/file.webm", 30_000L, 1_500_000L)
        val updated = repo.findById(task.id)!!
        assert(updated.recordingUrl == "https://example.com/video.webm")
        assert(updated.storageKey == "demo-recordings/key/file.webm")
        assert(updated.durationMs == 30_000L)
        assert(updated.fileSizeBytes == 1_500_000L)
        assert(updated.status == DemoStatus.COMPLETED)
    }

    @Test
    fun `findPending returns only pending tasks`() = runTest {
        repo.save(createTask(status = DemoStatus.PENDING))
        repo.save(createTask(status = DemoStatus.PENDING))
        repo.save(createTask(status = DemoStatus.COMPLETED))
        val pending = repo.findPending()
        assert(pending.size == 2)
        assert(pending.all { it.status == DemoStatus.PENDING })
    }

    @Test
    fun `findAll returns all tasks`() = runTest {
        repo.save(createTask())
        repo.save(createTask())
        assert(repo.findAll().size >= 2)
    }

    @Test
    fun `deleteOlderThan respects isKept flag`() = runTest {
        val oldKept = createTask(status = DemoStatus.COMPLETED, createdAt = "2020-01-01T00:00:00Z", isKept = true)
        val oldUnkept = createTask(status = DemoStatus.COMPLETED, createdAt = "2020-01-01T00:00:00Z")
        repo.save(oldKept)
        repo.save(oldUnkept)
        val deleted = repo.deleteOlderThan("2021-01-01T00:00:00Z", 10)
        assert(deleted == 1)
        assert(repo.findById(oldKept.id) != null)
        assert(repo.findById(oldUnkept.id) == null)
    }

    @Test
    fun `countByStatus returns correct count`() = runTest {
        repo.save(createTask(status = DemoStatus.COMPLETED))
        repo.save(createTask(status = DemoStatus.COMPLETED))
        repo.save(createTask(status = DemoStatus.FAILED))
        assert(repo.countByStatus(DemoStatus.COMPLETED) == 2)
        assert(repo.countByStatus(DemoStatus.FAILED) == 1)
        assert(repo.countByStatus(DemoStatus.PENDING) == 0)
    }

    @Test
    fun `sumFileSizes returns total`() = runTest {
        repo.save(createTask(status = DemoStatus.COMPLETED, fileSizeBytes = 1000L))
        repo.save(createTask(status = DemoStatus.COMPLETED, fileSizeBytes = 2000L))
        assert(repo.sumFileSizes() == 3000L)
    }

    @Test
    fun `updateKeepFlag works`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateKeepFlag(task.id, true)
        assert(repo.findById(task.id)!!.isKept)
        repo.updateKeepFlag(task.id, false)
        assert(!repo.findById(task.id)!!.isKept)
    }

    @Test
    fun `findOlderThan returns only old unkept tasks`() = runTest {
        val oldUnkept = createTask(createdAt = "2020-01-01T00:00:00Z")
        val oldKept = createTask(createdAt = "2020-01-01T00:00:00Z", isKept = true)
        val recent = createTask()
        repo.save(oldUnkept)
        repo.save(oldKept)
        repo.save(recent)
        val old = repo.findOlderThan("2021-01-01T00:00:00Z")
        assert(old.size == 1)
        assert(old[0].id == oldUnkept.id)
    }

    @Test
    fun `findByStatus filters correctly`() = runTest {
        repo.save(createTask(status = DemoStatus.COMPLETED))
        repo.save(createTask(status = DemoStatus.FAILED))
        assert(repo.findByStatus(DemoStatus.COMPLETED).size == 1)
        assert(repo.findByStatus(DemoStatus.FAILED).size == 1)
    }

    @Test
    fun `updateHtmlReportKey works`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateHtmlReportKey(task.id, "html/report.html")
        assert(repo.findById(task.id)!!.htmlReportKey == "html/report.html")
    }

    @Test
    fun `updateFallbackFrom works`() = runTest {
        val task = createTask()
        repo.save(task)
        repo.updateFallbackFrom(task.id, DemoPlatform.ASCIINEMA.name)
        assert(repo.findById(task.id)!!.fallbackFrom == "ASCIINEMA")
    }

    private fun createTask(
        issueId: String = "issue-${UUID.randomUUID()}",
        status: DemoStatus = DemoStatus.PENDING,
        platform: DemoPlatform = DemoPlatform.PLAYWRIGHT,
        trigger: DemoTrigger = DemoTrigger.MANUAL,
        createdAt: String? = null,
        isKept: Boolean = false,
        fileSizeBytes: Long? = null
    ): DemoTask {
        val now = createdAt ?: Instant.now().toString()
        return DemoTask(
            id = UUID.randomUUID().toString(), issueId = issueId,
            issueIdentifier = "KONC-${(100..999).random()}",
            projectSlug = "test-project", platform = platform, status = status,
            trigger = trigger, createdAt = now, updatedAt = now,
            isKept = isKept, fileSizeBytes = fileSizeBytes
        )
    }
}
