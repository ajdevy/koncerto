package com.flexsentlabs.koncerto.e2e

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.review.ParseStatus
import com.flexsentlabs.koncerto.core.review.ReviewEligibility
import com.flexsentlabs.koncerto.core.review.ReviewMode
import com.flexsentlabs.koncerto.core.review.ReviewOutputParser
import com.flexsentlabs.koncerto.core.review.ReviewPolicy
import com.flexsentlabs.koncerto.core.review.RiskRouter
import com.flexsentlabs.koncerto.core.review.RiskTier
import com.flexsentlabs.koncerto.metrics.SqliteMetricsRepository
import com.flexsentlabs.koncerto.orchestrator.review.FindingOutcomeTracker
import com.flexsentlabs.koncerto.orchestrator.review.ReviewDiffInspector
import com.flexsentlabs.koncerto.orchestrator.review.ReviewRunContext
import com.flexsentlabs.koncerto.orchestrator.review.ReviewTelemetryRecorder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * End-to-end coverage of the review-quality pipeline against a real git repo and a real
 * SQLite store, with the model response injected. Deliberately NOT tagged `e2e`: it is
 * deterministic and offline, so it runs on every build. [FreeModelReviewE2eTest] exercises
 * the same path against a real free model and is gated behind a system property.
 *
 * Covers: git diff inspection → eligibility → risk routing → structured parse →
 * publication gate → telemetry persistence → outcome tracking → baseline aggregation.
 */
class ReviewPipelineE2eTest {

    private fun git(repo: Path, vararg args: String) {
        val proc = ProcessBuilder(listOf("git") + args)
            .directory(repo.toFile())
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    }

    private fun initRepo(): Path {
        val repo = Files.createTempDirectory("koncerto-review-e2e-")
        git(repo, "init", "-q")
        git(repo, "config", "user.email", "e2e@koncerto.test")
        git(repo, "config", "user.name", "Koncerto E2E")
        git(repo, "config", "commit.gpgsign", "false")
        return repo
    }

    private fun commitAll(repo: Path, message: String) {
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", message, "--no-verify")
    }

    @Test
    fun `full pipeline persists gated findings and computes baseline`() = runBlocking<Unit> {
        val repo = initRepo()
        val dbPath = Files.createTempFile("review-e2e-", ".db")
        try {
            // Baseline commit, then a real code change on top so HEAD~1 has a diff.
            Files.writeString(repo.resolve("README.md"), "# demo\n")
            commitAll(repo, "init")

            Files.createDirectories(repo.resolve("src"))
            Files.writeString(repo.resolve("src/Auth.kt"), """
                package demo
                class Auth {
                    fun check(token: String, expected: String): Boolean = token == expected
                }
            """.trimIndent())
            commitAll(repo, "add auth")

            // 1. Diff inspection against the real repo
            val diff = ReviewDiffInspector().inspect(repo)
            assertThat(diff.changedFiles).isEqualTo(listOf("src/Auth.kt"))
            assertThat(diff.totalLinesChanged).isGreaterThan(0)
            assertThat(diff.commitSha).isNotNull()

            // 2. Eligibility + routing — auth path is configured critical
            val policy = ReviewPolicy(
                mode = ReviewMode.ADVISORY,
                criticalGlobs = listOf("**/Auth.kt")
            )
            val eligibility = ReviewEligibility.evaluate(diff.changedFiles, policy)
            assertThat(eligibility.shouldReview).isTrue()
            val tier = RiskRouter.classify(diff.changedFiles, diff.totalLinesChanged, policy)
            assertThat(tier).isEqualTo(RiskTier.CRITICAL)

            // 3. Parse a realistic model response (JSON envelope + fenced findings)
            val modelStdout = """
                {"type":"result","result":"❌ **Changes requested** — timing attack in token check.\n**1 blocking · 0 warnings · 1 suggestions** · 1 files\n\n```review-findings\n{\"findings\":[{\"seq\":1,\"category\":\"security\",\"severity\":\"critical\",\"confidence\":0.93,\"file\":\"src/Auth.kt\",\"line\":3,\"description\":\"Token compared with == enabling timing attacks\",\"expectedAction\":\"Use constant-time comparison\",\"evidence\":\"Auth.kt:3\"},{\"seq\":2,\"category\":\"conventions\",\"severity\":\"suggestion\",\"confidence\":0.3,\"file\":\"src/Auth.kt\",\"line\":2,\"description\":\"Could be a data class\",\"expectedAction\":\"Consider data class\",\"evidence\":\"style\"}]}\n```","usage":{"input_tokens":2400,"output_tokens":310},"duration_ms":8200,"is_error":false}
            """.trimIndent()
            val parsed = ReviewOutputParser.parse(modelStdout, promptVersion = "2.0")
            assertThat(parsed.parseStatus).isEqualTo(ParseStatus.OK)
            assertThat(parsed.verdictPass).isFalse()
            assertThat(parsed.usage.totalTokens).isEqualTo(2710L)

            // 4. Gate + persistence into a real SQLite store
            val metrics = SqliteMetricsRepository(dbPath.toString())
            val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-e2e" })
            val ctx = ReviewRunContext(
                issueId = "issue-e2e", issueIdentifier = "E2E-1", projectSlug = "e2e",
                attempt = 1, commitSha = diff.commitSha, prNumber = 1, model = "free",
                riskTier = tier, reviewMode = ReviewMode.ADVISORY
            )
            val gate = recorder.record(ctx, parsed, policy).gate

            // The low-confidence suggestion is dropped; the critical is published.
            assertThat(gate.published.size).isEqualTo(1)
            assertThat(gate.published[0].category).isEqualTo("security")
            assertThat(gate.dropped.size).isEqualTo(1)

            val storedRun = metrics.runs("e2e").single()
            assertThat(storedRun.verdict).isEqualTo("fail")
            assertThat(storedRun.riskTier).isEqualTo("critical")
            assertThat(storedRun.reviewMode).isEqualTo("advisory")
            assertThat(storedRun.findingsTotal).isEqualTo(2)
            assertThat(storedRun.findingsPublished).isEqualTo(1)
            assertThat(storedRun.inputTokens).isEqualTo(2400L)
            assertThat(storedRun.promptVersion).isEqualTo("2.0")

            // 5. Fix agent reports a disposition → outcome tracked
            Files.writeString(repo.resolve(".review-fix-report.json"),
                """[{"findingId":"run-e2e-1","disposition":"fixed","note":"constant-time compare"}]""")
            val tracker = FindingOutcomeTracker(metrics)
            assertThat(tracker.applyFixReport(repo)).isEqualTo(1)

            // 6. Baseline reflects the high-evidence outcome
            val baseline = metrics.baseline("e2e", windowDays = 30)
            assertThat(baseline.totalRuns).isEqualTo(1)
            assertThat(baseline.publishedFindings).isEqualTo(1)
            assertThat(baseline.highEvidenceOutcomes).isEqualTo(1)
            assertThat(baseline.highEvidenceRate).isEqualTo(1.0)
            assertThat(baseline.tokensPerUsefulFinding).isEqualTo(2710.0)
        } finally {
            repo.toFile().deleteRecursively()
            Files.deleteIfExists(dbPath)
        }
    }

    @Test
    fun `artifact-only diff is skipped and recorded with zero tokens`() = runBlocking<Unit> {
        val repo = initRepo()
        val dbPath = Files.createTempFile("review-e2e-skip-", ".db")
        try {
            Files.writeString(repo.resolve("README.md"), "# demo\n")
            commitAll(repo, "init")

            // A commit containing only Koncerto pipeline artifacts — must not burn a review.
            Files.createDirectories(repo.resolve(".koncerto"))
            Files.writeString(repo.resolve(".koncerto/state.jsonl"), """{"e":1}""")
            commitAll(repo, "orchestration state")

            val diff = ReviewDiffInspector().inspect(repo)
            assertThat(diff.changedFiles).isEqualTo(listOf(".koncerto/state.jsonl"))

            val decision = ReviewEligibility.evaluate(diff.changedFiles, ReviewPolicy.DEFAULT)
            assertThat(decision.shouldReview).isFalse()
            assertThat(decision.reason).isEqualTo("skipped_artifact_only")

            val metrics = SqliteMetricsRepository(dbPath.toString())
            val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-skip" })
            recorder.recordSkipped(
                ReviewRunContext(
                    issueId = "issue-skip", issueIdentifier = "E2E-2", projectSlug = "e2e",
                    attempt = 1, commitSha = diff.commitSha, prNumber = null, model = "free",
                    riskTier = RiskTier.LOW, reviewMode = ReviewMode.ADVISORY
                ),
                decision.reason
            )

            val baseline = metrics.baseline("e2e")
            assertThat(baseline.skippedRuns).isEqualTo(1)
            assertThat(baseline.totalTokens).isEqualTo(0L)
        } finally {
            repo.toFile().deleteRecursively()
            Files.deleteIfExists(dbPath)
        }
    }

    @Test
    fun `docs-only diff is skipped`() {
        val repo = initRepo()
        try {
            Files.writeString(repo.resolve("README.md"), "# demo\n")
            commitAll(repo, "init")
            Files.writeString(repo.resolve("README.md"), "# demo\n\nMore docs.\n")
            Files.writeString(repo.resolve("CHANGELOG.md"), "## 1.0\n")
            commitAll(repo, "docs")

            val diff = ReviewDiffInspector().inspect(repo)
            val decision = ReviewEligibility.evaluate(diff.changedFiles, ReviewPolicy.DEFAULT)
            assertThat(decision.shouldReview).isFalse()
            assertThat(decision.reason).isEqualTo("skipped_docs_only")
        } finally {
            repo.toFile().deleteRecursively()
        }
    }
}
