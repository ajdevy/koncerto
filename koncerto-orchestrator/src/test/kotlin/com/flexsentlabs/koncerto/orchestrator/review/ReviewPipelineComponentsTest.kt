package com.flexsentlabs.koncerto.orchestrator.review

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThan
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.review.ReviewFinding
import com.flexsentlabs.koncerto.core.review.ReviewMode
import com.flexsentlabs.koncerto.core.review.ReviewParseResult
import com.flexsentlabs.koncerto.core.review.ReviewPolicy
import com.flexsentlabs.koncerto.core.review.ReviewUsage
import com.flexsentlabs.koncerto.core.review.RiskTier
import com.flexsentlabs.koncerto.core.review.Severity
import com.flexsentlabs.koncerto.metrics.ReviewBaseline
import com.flexsentlabs.koncerto.metrics.ReviewFindingRecord
import com.flexsentlabs.koncerto.metrics.ReviewMetricsRepository
import com.flexsentlabs.koncerto.metrics.ReviewRunRecord
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** In-memory fake of the review metrics store for orchestrator component tests. */
class FakeReviewMetrics : ReviewMetricsRepository {
    val recordedRuns = mutableListOf<ReviewRunRecord>()
    val recordedFindings = mutableListOf<ReviewFindingRecord>()

    override suspend fun recordRun(run: ReviewRunRecord) { recordedRuns.add(run) }
    override suspend fun recordFindings(findings: List<ReviewFindingRecord>) { recordedFindings.addAll(findings) }
    override suspend fun updateOutcome(findingId: String, outcome: String, outcomeSource: String) {
        recordedFindings.replaceAll {
            if (it.findingId == findingId) it.copy(outcome = outcome, outcomeSource = outcomeSource) else it
        }
    }
    override suspend fun setHumanLabel(findingId: String, label: String) {
        recordedFindings.replaceAll { if (it.findingId == findingId) it.copy(humanLabel = label) else it }
    }
    override suspend fun runs(projectSlug: String?, limit: Int) = recordedRuns.toList()
    override suspend fun findingsForRun(runId: String) = recordedFindings.filter { it.runId == runId }
    override suspend fun publishedFindingsForIssue(issueId: String) =
        recordedFindings.filter { it.issueId == issueId && it.published }
    override suspend fun baseline(projectSlug: String?, windowDays: Int) = ReviewBaseline(
        windowDays, recordedRuns.size, 0, 0, 0, recordedFindings.size, 0, 0, 0.0, 0, 0, 0.0, 0, 0.0,
        emptyMap(), emptyMap()
    )
}

class ReviewPipelineComponentsTest {

    private fun ctx() = ReviewRunContext(
        issueId = "issue-1", issueIdentifier = "ABC-1", projectSlug = "proj", attempt = 1,
        commitSha = "sha", prNumber = 7, model = "free", riskTier = RiskTier.STANDARD,
        reviewMode = ReviewMode.ADVISORY
    )

    private fun finding(seq: Int, sev: Severity, conf: Double?, file: String = "A.kt", line: Int = 10) =
        ReviewFinding(seq, "correctness", sev, conf, file, line, "d$seq", "fix", "ev")

    // ---- ReviewDiffInspector ----

    @Test
    fun `diff inspector parses names numstat and sha`() {
        val fakeRunner: ProcRunner = { command, _ ->
            when {
                command.contains("--name-only") -> ProcResult(0, "src/A.kt\nsrc/B.kt\n")
                command.contains("--numstat") -> ProcResult(0, "10\t2\tsrc/A.kt\n5\t0\tsrc/B.kt\n")
                command.contains("rev-parse") -> ProcResult(0, "deadbeef\n")
                else -> ProcResult(1, "")
            }
        }
        val diff = ReviewDiffInspector(fakeRunner).inspect(Path.of("/tmp"))
        assertThat(diff.changedFiles).isEqualTo(listOf("src/A.kt", "src/B.kt"))
        assertThat(diff.totalLinesChanged).isEqualTo(17)
        assertThat(diff.commitSha).isEqualTo("deadbeef")
    }

    @Test
    fun `numstat ignores binary dash markers`() {
        val fakeRunner: ProcRunner = { command, _ ->
            when {
                command.contains("--name-only") -> ProcResult(0, "img.png\n")
                command.contains("--numstat") -> ProcResult(0, "-\t-\timg.png\n")
                else -> ProcResult(0, "sha\n")
            }
        }
        val diff = ReviewDiffInspector(fakeRunner).inspect(Path.of("/tmp"))
        assertThat(diff.totalLinesChanged).isEqualTo(0)
    }

    // ---- ReviewTelemetryRecorder + gate ----

    @Test
    fun `recorder applies gate and persists run and findings`() = runBlocking<Unit> {
        val metrics = FakeReviewMetrics()
        val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-x" })
        val parsed = ReviewParseResult(
            verdictPass = false,
            findings = listOf(
                finding(1, Severity.CRITICAL, 0.9),      // published
                finding(2, Severity.SUGGESTION, 0.4)     // dropped (below 0.85)
            ),
            usage = ReviewUsage(100, 50, 150, 400, false),
            promptVersion = "2.0"
        )
        val gate = recorder.record(ctx(), parsed, ReviewPolicy.DEFAULT).gate

        assertThat(gate.published.size).isEqualTo(1)
        assertThat(metrics.recordedRuns.size).isEqualTo(1)
        assertThat(metrics.recordedRuns[0].findingsPublished).isEqualTo(1)
        assertThat(metrics.recordedRuns[0].verdict).isEqualTo("fail")
        assertThat(metrics.recordedFindings.size).isEqualTo(2)
        val dropped = metrics.recordedFindings.first { it.seq == 2 }
        assertThat(dropped.published).isEqualTo(false)
        assertThat(dropped.dropReason).isNotNull()
    }

    @Test
    fun `recorder records skipped run with zero tokens`() = runBlocking<Unit> {
        val metrics = FakeReviewMetrics()
        val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-skip" })
        recorder.recordSkipped(ctx(), "skipped_artifact_only")
        assertThat(metrics.recordedRuns.size).isEqualTo(1)
        assertThat(metrics.recordedRuns[0].eligibility).isEqualTo("skipped_artifact_only")
        assertThat(metrics.recordedRuns[0].verdict).isEqualTo("skipped")
        assertThat(metrics.recordedRuns[0].inputTokens).isEqualTo(0L)
    }

    // ---- FindingOutcomeTracker ----

    @Test
    fun `fix report sets outcomes`() = runBlocking<Unit> {
        val metrics = FakeReviewMetrics()
        val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-1" })
        recorder.record(ctx(), ReviewParseResult(false, listOf(finding(1, Severity.CRITICAL, 0.9))), ReviewPolicy.DEFAULT)

        val ws = Files.createTempDirectory("fix-report-")
        Files.writeString(ws.resolve(".review-fix-report.json"),
            """[{"findingId":"run-1-1","disposition":"fixed","note":"done"}]""")

        val tracker = FindingOutcomeTracker(metrics)
        val applied = tracker.applyFixReport(ws)
        assertThat(applied).isEqualTo(1)
        assertThat(metrics.recordedFindings.first { it.findingId == "run-1-1" }.outcome).isEqualTo("fixed")
        ws.toFile().deleteRecursively()
    }

    @Test
    fun `rereview marks vanished prior findings as likely fixed`() = runBlocking<Unit> {
        val metrics = FakeReviewMetrics()
        val recorder = ReviewTelemetryRecorder(metrics, idGen = { "run-1" })
        recorder.record(ctx(), ReviewParseResult(false, listOf(
            finding(1, Severity.CRITICAL, 0.9, "A.kt", 10),
            finding(2, Severity.CRITICAL, 0.9, "B.kt", 20)
        )), ReviewPolicy.DEFAULT)

        val tracker = FindingOutcomeTracker(metrics)
        // Re-review only still reports the A.kt defect → B.kt one is likely fixed.
        val updated = tracker.applyRereview("issue-1", listOf(finding(1, Severity.CRITICAL, 0.9, "A.kt", 12)))
        assertThat(updated).isEqualTo(1)
        assertThat(metrics.recordedFindings.first { it.findingId == "run-1-2" }.outcome).isEqualTo("likely_fixed")
        assertThat(metrics.recordedFindings.first { it.findingId == "run-1-1" }.outcome).isNull()
    }

    // ---- SpecialistReviewCoordinator ----

    @Test
    fun `specialist coordinator merges and tags findings`() = runBlocking<Unit> {
        val coordinator = SpecialistReviewCoordinator { path ->
            when {
                path.contains("security") -> ReviewParseResult(
                    verdictPass = false,
                    findings = listOf(finding(1, Severity.CRITICAL, 0.9, "Auth.kt", 5)),
                    usage = ReviewUsage(100, 40, 140, durationMs = 1000)
                )
                path.contains("reliability") -> ReviewParseResult(
                    verdictPass = true,
                    findings = listOf(finding(1, Severity.WARNING, 0.7, "Pool.kt", 30)),
                    usage = ReviewUsage(80, 30, 110, durationMs = 500)
                )
                else -> null
            }
        }
        val result = coordinator.run(listOf("prompts/review-security.md", "prompts/review-reliability.md"))
        assertThat(result.findings.size).isEqualTo(2)
        assertThat(result.usage.totalTokens).isEqualTo(250L)
        assertThat(result.usage.durationMs).isEqualTo(1500L)   // sequential → durations add
        assertThat(result.verdictPass).isEqualTo(false)   // has a critical
        val securityFinding = result.findings.first { it.file == "Auth.kt" }
        assertThat(securityFinding.specialist).isEqualTo("security")
        assertThat(result.specialistCount).isEqualTo(2)
    }

    @Test
    fun `specialist coordinator runs sequentially`() = runBlocking<Unit> {
        // Order matters: specialists share one workspace handoff file, so overlapping runs
        // would clobber each other. Assert they are invoked one at a time, in order.
        val order = mutableListOf<String>()
        var inFlight = 0
        val coordinator = SpecialistReviewCoordinator { path ->
            inFlight++
            check(inFlight == 1) { "specialists overlapped — they must run sequentially" }
            order.add(path)
            inFlight--
            ReviewParseResult(verdictPass = true, findings = emptyList(), usage = ReviewUsage(1, 1, 2))
        }
        coordinator.run(listOf("a-security.md", "b-reliability.md", "c-architecture.md"))
        assertThat(order).isEqualTo(listOf("a-security.md", "b-reliability.md", "c-architecture.md"))
    }

    @Test
    fun `specialist coordinator skips specialists that fail`() = runBlocking<Unit> {
        val coordinator = SpecialistReviewCoordinator { path ->
            if (path.contains("security")) {
                ReviewParseResult(
                    verdictPass = true,
                    findings = listOf(finding(1, Severity.WARNING, 0.8, "A.kt", 1)),
                    usage = ReviewUsage(10, 5, 15)
                )
            } else null   // e.g. the model failed or emitted nothing parseable
        }
        val result = coordinator.run(listOf("review-security.md", "review-reliability.md"))
        assertThat(result.specialistCount).isEqualTo(1)
        assertThat(result.findings.size).isEqualTo(1)
        assertThat(result.verdictPass).isTrue()
    }

    // ---- ReviewContextBuilder ----

    @Test
    fun `context builder includes invariants and respects budget`() {
        val ws = Files.createTempDirectory("ctx-")
        Files.writeString(ws.resolve("review-invariants.md"), "INV-1: never log secrets")
        val builder = ReviewContextBuilder()
        val pack = builder.build(
            workspacePath = ws,
            issueTitle = "Add auth",
            issueDescription = "Implement login",
            acceptanceCriteria = "User can log in",
            prBody = "This PR adds login",
            changedFiles = listOf("src/Auth.kt"),
            policy = ReviewPolicy.DEFAULT
        )
        assertThat(pack.text.contains("INV-1")).isTrue()
        assertThat(pack.text.contains("Implement login")).isTrue()
        assertThat(pack.composition.containsKey("invariants")).isTrue()
        ws.toFile().deleteRecursively()
    }

    @Test
    fun `neutralizeTemplating defangs liquid delimiters from untrusted repo content`() {
        // A file in the repo under review contains template syntax. Our prompt is Liquid-rendered
        // downstream, so this must not survive as live syntax.
        val hostile = "Hello {{ issue.description }} and {% if admin %}secret{% endif %}"
        val safe = ReviewContextBuilder.neutralizeTemplating(hostile)

        assertThat(safe.contains("{{")).isEqualTo(false)
        assertThat(safe.contains("}}")).isEqualTo(false)
        assertThat(safe.contains("{%")).isEqualTo(false)
        assertThat(safe.contains("%}")).isEqualTo(false)
        // Content is preserved for the reader, just not executable.
        assertThat(safe.contains("issue.description")).isTrue()
    }

    @Test
    fun `neutralizeTemplating leaves ordinary code untouched`() {
        val code = "fun f(x: Int) = mapOf(\"a\" to 1)"
        assertThat(ReviewContextBuilder.neutralizeTemplating(code)).isEqualTo(code)
    }

    @Test
    fun `context builder truncates to budget`() {
        val ws = Files.createTempDirectory("ctx2-")
        val builder = ReviewContextBuilder()
        val big = "x".repeat(5000)
        val pack = builder.build(
            workspacePath = ws,
            issueTitle = "T",
            issueDescription = big,
            acceptanceCriteria = null,
            prBody = null,
            changedFiles = emptyList(),
            policy = ReviewPolicy(contextBudgetChars = 500)
        )
        assertThat(pack.text.length).isGreaterThan(0)
        assertThat(pack.text.length <= 700).isTrue()
        ws.toFile().deleteRecursively()
    }
}
