package com.flexsentlabs.koncerto.metrics

import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ReviewMetricsRepositoryTest {

    private lateinit var dbPath: java.nio.file.Path
    private lateinit var repo: SqliteMetricsRepository

    @BeforeEach
    fun setup() {
        dbPath = Files.createTempFile("review-metrics-", ".db")
        repo = SqliteMetricsRepository(dbPath.toString())
    }

    @AfterEach
    fun teardown() {
        Files.deleteIfExists(dbPath)
    }

    private fun run(id: String, issue: String = "issue-1", eligibility: String = "reviewed",
                    published: Int = 1, tokens: Long = 1000) = ReviewRunRecord(
        runId = id, issueId = issue, issueIdentifier = "ABC-1", projectSlug = "proj",
        attempt = 1, commitSha = "abc", prNumber = 42, model = "free", promptVersion = "2.0",
        riskTier = "standard", reviewMode = "advisory", eligibility = eligibility, parseStatus = "ok",
        verdict = "fail", findingsTotal = 2, findingsPublished = published, inputTokens = tokens,
        outputTokens = tokens / 2, durationMs = 500, contextPackJson = null,
        createdAt = java.time.Instant.now().toString()
    )

    private fun finding(id: String, runId: String, seq: Int, published: Boolean = true) = ReviewFindingRecord(
        findingId = id, runId = runId, seq = seq, specialist = null, category = "correctness",
        severity = "critical", confidence = 0.9, file = "A.kt", line = 10, description = "d",
        expectedAction = "fix", evidence = "e", published = published, dropReason = null,
        outcome = null, outcomeSource = null, humanLabel = null, issueId = "issue-1",
        updatedAt = java.time.Instant.now().toString()
    )

    @Test
    fun `records run and findings and reads them back`() = runBlocking<Unit> {
        repo.recordRun(run("run-1"))
        repo.recordFindings(listOf(finding("run-1-1", "run-1", 1), finding("run-1-2", "run-1", 2, published = false)))

        assertThat(repo.runs("proj")).hasSize(1)
        assertThat(repo.findingsForRun("run-1")).hasSize(2)
        assertThat(repo.publishedFindingsForIssue("issue-1")).hasSize(1)
    }

    @Test
    fun `outcome and human label updates persist`() = runBlocking<Unit> {
        repo.recordRun(run("run-1"))
        repo.recordFindings(listOf(finding("run-1-1", "run-1", 1)))

        repo.updateOutcome("run-1-1", "fixed", "fix_agent")
        repo.setHumanLabel("run-1-1", "accept")

        val f = repo.findingsForRun("run-1").first()
        assertThat(f.outcome).isEqualTo("fixed")
        assertThat(f.outcomeSource).isEqualTo("fix_agent")
        assertThat(f.humanLabel).isEqualTo("accept")
    }

    @Test
    fun `baseline computes high-evidence and false-positive rates`() = runBlocking<Unit> {
        repo.recordRun(run("run-1"))
        repo.recordFindings(listOf(
            finding("run-1-1", "run-1", 1),
            finding("run-1-2", "run-1", 2)
        ))
        repo.updateOutcome("run-1-1", "fixed", "fix_agent")     // high-evidence
        repo.setHumanLabel("run-1-2", "false_positive")          // FP

        val baseline = repo.baseline("proj", windowDays = 30)
        assertThat(baseline.totalRuns).isEqualTo(1)
        assertThat(baseline.publishedFindings).isEqualTo(2)
        assertThat(baseline.highEvidenceOutcomes).isEqualTo(1)
        assertThat(baseline.highEvidenceRate).isEqualTo(0.5)
        assertThat(baseline.humanLabeled).isEqualTo(1)
        assertThat(baseline.falsePositives).isEqualTo(1)
        assertThat(baseline.tokensPerUsefulFinding).isGreaterThan(0.0)
    }

    @Test
    fun `skipped run is recorded with zero tokens`() = runBlocking<Unit> {
        repo.recordRun(run("run-skip", eligibility = "skipped_artifact_only", published = 0, tokens = 0))
        val baseline = repo.baseline("proj")
        assertThat(baseline.skippedRuns).isEqualTo(1)
    }
}
