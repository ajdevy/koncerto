package com.anomaly.koncerto.metrics

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.size
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MetricsRepositoryTest {

    private lateinit var repo: MetricsRepository

    @BeforeEach
    fun setUp() {
        repo = SqliteMetricsRepository(":memory:")
    }

    @Test
    fun `updateAfterRun creates new record`() = runBlocking {
        repo.updateAfterRun(
            issueId = "1",
            issueIdentifier = "PROJ-1",
            projectSlug = "proj-a",
            result = "success",
            inputTokens = 100,
            outputTokens = 50,
            totalTokens = 150
        )

        val record = repo.findById("1")
        assertThat(record).isNotNull()
        assertThat(record!!.issueId).isEqualTo("1")
        assertThat(record.issueIdentifier).isEqualTo("PROJ-1")
        assertThat(record.projectSlug).isEqualTo("proj-a")
        assertThat(record.totalRuns).isEqualTo(1)
        assertThat(record.totalInputTokens).isEqualTo(100)
        assertThat(record.totalOutputTokens).isEqualTo(50)
        assertThat(record.totalTokens).isEqualTo(150)
        assertThat(record.lastResult).isEqualTo("success")
        assertThat(record.createdAt as String?).isNotNull()
        assertThat(record.updatedAt).isEqualTo(record.createdAt)
    }

    @Test
    fun `updateAfterRun accumulates on subsequent calls`() = runBlocking {
        repo.updateAfterRun(
            issueId = "1",
            issueIdentifier = "PROJ-1",
            projectSlug = "proj-a",
            result = "success",
            inputTokens = 100,
            outputTokens = 50,
            totalTokens = 150
        )
        repo.updateAfterRun(
            issueId = "1",
            issueIdentifier = "PROJ-1",
            projectSlug = "proj-a",
            result = "failure",
            inputTokens = 30,
            outputTokens = 10,
            totalTokens = 40
        )

        val record = repo.findById("1")
        assertThat(record).isNotNull()
        assertThat(record!!.totalRuns).isEqualTo(2)
        assertThat(record.totalInputTokens).isEqualTo(130)
        assertThat(record.totalOutputTokens).isEqualTo(60)
        assertThat(record.totalTokens).isEqualTo(190)
        assertThat(record.lastResult).isEqualTo("failure")
        assertThat(record.updatedAt != record.createdAt).isTrue()
    }

    @Test
    fun `findByProject filters correctly`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 10, 5, 15)
        repo.updateAfterRun("2", "PROJ-2", "proj-b", "success", 20, 10, 30)
        repo.updateAfterRun("3", "PROJ-3", "proj-a", "failure", 5, 2, 7)

        val projAResults = repo.findByProject("proj-a")
        assertThat(projAResults.size).isEqualTo(2)
        assertThat(projAResults.all { it.projectSlug == "proj-a" }).isTrue()

        val projBResults = repo.findByProject("proj-b")
        assertThat(projBResults.size).isEqualTo(1)
    }

    @Test
    fun `findByProject handles null project slug`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", null, "success", 10, 5, 15)
        repo.updateAfterRun("2", "PROJ-2", "proj-a", "success", 20, 10, 30)

        val nullResults = repo.findByProject(null)
        assertThat(nullResults.size).isEqualTo(1)
        assertThat(nullResults[0].issueId).isEqualTo("1")
    }

    @Test
    fun `findById returns record or null`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 10, 5, 15)

        val found = repo.findById("1")
        assertThat(found).isNotNull()
        assertThat(found!!.issueId).isEqualTo("1")

        val notFound = repo.findById("nonexistent")
        assertThat(notFound).isNull()
    }

    @Test
    fun `tokenHistory returns daily summaries`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 100, 50, 150)
        repo.updateAfterRun("2", "PROJ-2", "proj-a", "success", 200, 80, 280)
        repo.updateAfterRun("3", "PROJ-3", "proj-b", "failure", 10, 5, 15)

        val history = repo.tokenHistory(days = 30)
        assertThat(history.size).isEqualTo(1)
        val summary = history[0]
        assertThat(summary.inputTokens).isEqualTo(310)
        assertThat(summary.outputTokens).isEqualTo(135)
        assertThat(summary.totalTokens).isEqualTo(445)
    }

    @Test
    fun `tokenHistory respects days parameter`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 100, 50, 150)

        val history = repo.tokenHistory(days = 365)
        assertThat(history.size).isEqualTo(1)
    }

    @Test
    fun `findAll returns all records sorted by updated_at`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 10, 5, 15)
        repo.updateAfterRun("2", "PROJ-2", "proj-b", "success", 20, 10, 30)

        val all = repo.findAll()
        assertThat(all.size).isEqualTo(2)
        assertThat(all.map { it.issueId }).containsExactly("2", "1")
    }
}
