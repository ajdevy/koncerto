package com.flexsentlabs.koncerto.metrics

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteMetricsRepository(dbPath: String) : MetricsRepository, ReviewMetricsRepository {

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val lock = ReentrantLock()

    init {
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS issue_metrics (
                    issue_id TEXT PRIMARY KEY,
                    issue_identifier TEXT NOT NULL,
                    project_slug TEXT,
                    total_runs INTEGER DEFAULT 0,
                    total_input_tokens INTEGER DEFAULT 0,
                    total_output_tokens INTEGER DEFAULT 0,
                    total_tokens INTEGER DEFAULT 0,
                    last_result TEXT,
                    last_run_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS review_runs (
                    run_id TEXT PRIMARY KEY,
                    issue_id TEXT NOT NULL,
                    issue_identifier TEXT NOT NULL,
                    project_slug TEXT,
                    attempt INTEGER NOT NULL,
                    commit_sha TEXT,
                    pr_number INTEGER,
                    model TEXT,
                    prompt_version TEXT,
                    risk_tier TEXT,
                    review_mode TEXT,
                    eligibility TEXT NOT NULL,
                    parse_status TEXT,
                    verdict TEXT,
                    findings_total INTEGER DEFAULT 0,
                    findings_published INTEGER DEFAULT 0,
                    input_tokens INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    duration_ms INTEGER DEFAULT 0,
                    context_pack_json TEXT,
                    created_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS review_findings (
                    finding_id TEXT PRIMARY KEY,
                    run_id TEXT NOT NULL,
                    seq INTEGER NOT NULL,
                    specialist TEXT,
                    category TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    confidence REAL,
                    file TEXT,
                    line INTEGER,
                    description TEXT,
                    expected_action TEXT,
                    evidence TEXT,
                    published INTEGER NOT NULL DEFAULT 0,
                    drop_reason TEXT,
                    outcome TEXT,
                    outcome_source TEXT,
                    human_label TEXT,
                    issue_id TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_findings_run ON review_findings(run_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_findings_issue ON review_findings(issue_id)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_runs_project ON review_runs(project_slug)")
        }
    }

    override suspend fun updateAfterRun(
        issueId: String,
        issueIdentifier: String,
        projectSlug: String?,
        result: String,
        inputTokens: Long,
        outputTokens: Long,
        totalTokens: Long
    ) {
        withContext(Dispatchers.IO) {
            lock.withLock {
                val now = Instant.now().toString()
                val existing = findExisting(issueId)
                if (existing != null) {
                    connection.prepareStatement(
                        """
                        UPDATE issue_metrics SET
                            issue_identifier = ?,
                            project_slug = ?,
                            total_runs = total_runs + 1,
                            total_input_tokens = total_input_tokens + ?,
                            total_output_tokens = total_output_tokens + ?,
                            total_tokens = total_tokens + ?,
                            last_result = ?,
                            last_run_at = ?,
                            updated_at = ?
                        WHERE issue_id = ?
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, issueIdentifier)
                        stmt.setString(2, projectSlug)
                        stmt.setLong(3, inputTokens)
                        stmt.setLong(4, outputTokens)
                        stmt.setLong(5, totalTokens)
                        stmt.setString(6, result)
                        stmt.setString(7, now)
                        stmt.setString(8, now)
                        stmt.setString(9, issueId)
                        stmt.executeUpdate()
                    }
                } else {
                    connection.prepareStatement(
                        """
                        INSERT INTO issue_metrics
                            (issue_id, issue_identifier, project_slug, total_runs,
                             total_input_tokens, total_output_tokens, total_tokens,
                             last_result, last_run_at, created_at, updated_at)
                        VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent()
                    ).use { stmt ->
                        stmt.setString(1, issueId)
                        stmt.setString(2, issueIdentifier)
                        stmt.setString(3, projectSlug)
                        stmt.setLong(4, inputTokens)
                        stmt.setLong(5, outputTokens)
                        stmt.setLong(6, totalTokens)
                        stmt.setString(7, result)
                        stmt.setString(8, now)
                        stmt.setString(9, now)
                        stmt.setString(10, now)
                        stmt.executeUpdate()
                    }
                }
            }
        }
    }

    override suspend fun findAll(): List<IssueMetrics> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM issue_metrics ORDER BY updated_at DESC"
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<IssueMetrics>()
                    while (rs.next()) result.add(rs.toIssueMetrics())
                    result
                }
            }
        }
    }

    override suspend fun findByProject(projectSlug: String?): List<IssueMetrics> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (projectSlug == null) {
                connection.prepareStatement(
                    "SELECT * FROM issue_metrics WHERE project_slug IS NULL ORDER BY updated_at DESC"
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<IssueMetrics>()
                        while (rs.next()) result.add(rs.toIssueMetrics())
                        result
                    }
                }
            } else {
                connection.prepareStatement(
                    "SELECT * FROM issue_metrics WHERE project_slug = ? ORDER BY updated_at DESC"
                ).use { stmt ->
                    stmt.setString(1, projectSlug)
                    stmt.executeQuery().use { rs ->
                        val result = mutableListOf<IssueMetrics>()
                        while (rs.next()) result.add(rs.toIssueMetrics())
                        result
                    }
                }
            }
        }
    }

    override suspend fun findById(issueId: String): IssueMetrics? = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "SELECT * FROM issue_metrics WHERE issue_id = ?"
            ).use { stmt ->
                stmt.setString(1, issueId)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.toIssueMetrics() else null
                }
            }
        }
    }

    override suspend fun tokenHistory(days: Int): List<TokenDaySummary> = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                """
                SELECT
                    SUBSTR(last_run_at, 1, 10) AS date,
                    SUM(total_input_tokens) AS input_tokens,
                    SUM(total_output_tokens) AS output_tokens,
                    SUM(total_tokens) AS total_tokens
                FROM issue_metrics
                WHERE last_run_at IS NOT NULL
                  AND last_run_at >= datetime('now', '-' || ? || ' days')
                GROUP BY date
                ORDER BY date ASC
                """.trimIndent()
            ).use { stmt ->
                stmt.setInt(1, days)
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<TokenDaySummary>()
                    while (rs.next()) result.add(
                        TokenDaySummary(
                            date = rs.getString("date"),
                            inputTokens = rs.getLong("input_tokens"),
                            outputTokens = rs.getLong("output_tokens"),
                            totalTokens = rs.getLong("total_tokens")
                        )
                    )
                    result
                }
            }
        }
    }

    private fun findExisting(issueId: String): IssueMetrics? {
        return connection.prepareStatement(
            "SELECT * FROM issue_metrics WHERE issue_id = ?"
        ).use { stmt ->
            stmt.setString(1, issueId)
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.toIssueMetrics() else null
            }
        }
    }

    private fun ResultSet.toIssueMetrics() = IssueMetrics(
        issueId = getString("issue_id"),
        issueIdentifier = getString("issue_identifier"),
        projectSlug = getString("project_slug"),
        totalRuns = getInt("total_runs"),
        totalInputTokens = getLong("total_input_tokens"),
        totalOutputTokens = getLong("total_output_tokens"),
        totalTokens = getLong("total_tokens"),
        lastResult = getString("last_result"),
        lastRunAt = getString("last_run_at"),
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at")
    )

    // ---- ReviewMetricsRepository (Epic 18) ----

    override suspend fun recordRun(run: ReviewRunRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                """
                INSERT OR REPLACE INTO review_runs
                    (run_id, issue_id, issue_identifier, project_slug, attempt, commit_sha,
                     pr_number, model, prompt_version, risk_tier, review_mode, eligibility,
                     parse_status, verdict, findings_total, findings_published, input_tokens,
                     output_tokens, duration_ms, context_pack_json, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent()
            ).use { s ->
                s.setString(1, run.runId)
                s.setString(2, run.issueId)
                s.setString(3, run.issueIdentifier)
                s.setString(4, run.projectSlug)
                s.setInt(5, run.attempt)
                s.setString(6, run.commitSha)
                if (run.prNumber != null) s.setInt(7, run.prNumber) else s.setNull(7, java.sql.Types.INTEGER)
                s.setString(8, run.model)
                s.setString(9, run.promptVersion)
                s.setString(10, run.riskTier)
                s.setString(11, run.reviewMode)
                s.setString(12, run.eligibility)
                s.setString(13, run.parseStatus)
                s.setString(14, run.verdict)
                s.setInt(15, run.findingsTotal)
                s.setInt(16, run.findingsPublished)
                s.setLong(17, run.inputTokens)
                s.setLong(18, run.outputTokens)
                s.setLong(19, run.durationMs)
                s.setString(20, run.contextPackJson)
                s.setString(21, run.createdAt)
                s.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun recordFindings(findings: List<ReviewFindingRecord>) = withContext(Dispatchers.IO) {
        if (findings.isEmpty()) return@withContext
        lock.withLock {
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    """
                    INSERT OR REPLACE INTO review_findings
                        (finding_id, run_id, seq, specialist, category, severity, confidence,
                         file, line, description, expected_action, evidence, published,
                         drop_reason, outcome, outcome_source, human_label, issue_id, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """.trimIndent()
                ).use { s ->
                    for (f in findings) {
                        s.setString(1, f.findingId)
                        s.setString(2, f.runId)
                        s.setInt(3, f.seq)
                        s.setString(4, f.specialist)
                        s.setString(5, f.category)
                        s.setString(6, f.severity)
                        if (f.confidence != null) s.setDouble(7, f.confidence) else s.setNull(7, java.sql.Types.REAL)
                        s.setString(8, f.file)
                        if (f.line != null) s.setInt(9, f.line) else s.setNull(9, java.sql.Types.INTEGER)
                        s.setString(10, f.description)
                        s.setString(11, f.expectedAction)
                        s.setString(12, f.evidence)
                        s.setInt(13, if (f.published) 1 else 0)
                        s.setString(14, f.dropReason)
                        s.setString(15, f.outcome)
                        s.setString(16, f.outcomeSource)
                        s.setString(17, f.humanLabel)
                        s.setString(18, f.issueId)
                        s.setString(19, f.updatedAt)
                        s.addBatch()
                    }
                    s.executeBatch()
                }
                connection.commit()
            } catch (e: Exception) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
        Unit
    }

    override suspend fun updateOutcome(findingId: String, outcome: String, outcomeSource: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                connection.prepareStatement(
                    "UPDATE review_findings SET outcome = ?, outcome_source = ?, updated_at = ? WHERE finding_id = ?"
                ).use { s ->
                    s.setString(1, outcome)
                    s.setString(2, outcomeSource)
                    s.setString(3, Instant.now().toString())
                    s.setString(4, findingId)
                    s.executeUpdate()
                }
            }
            Unit
        }

    override suspend fun setHumanLabel(findingId: String, label: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            connection.prepareStatement(
                "UPDATE review_findings SET human_label = ?, updated_at = ? WHERE finding_id = ?"
            ).use { s ->
                s.setString(1, label)
                s.setString(2, Instant.now().toString())
                s.setString(3, findingId)
                s.executeUpdate()
            }
        }
        Unit
    }

    override suspend fun runs(projectSlug: String?, limit: Int): List<ReviewRunRecord> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val sql = if (projectSlug == null) {
                    "SELECT * FROM review_runs ORDER BY created_at DESC LIMIT ?"
                } else {
                    "SELECT * FROM review_runs WHERE project_slug = ? ORDER BY created_at DESC LIMIT ?"
                }
                connection.prepareStatement(sql).use { s ->
                    if (projectSlug == null) {
                        s.setInt(1, limit)
                    } else {
                        s.setString(1, projectSlug)
                        s.setInt(2, limit)
                    }
                    s.executeQuery().use { rs ->
                        val out = mutableListOf<ReviewRunRecord>()
                        while (rs.next()) out.add(rs.toReviewRun())
                        out
                    }
                }
            }
        }

    override suspend fun findingsForRun(runId: String): List<ReviewFindingRecord> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                connection.prepareStatement(
                    "SELECT * FROM review_findings WHERE run_id = ? ORDER BY seq ASC"
                ).use { s ->
                    s.setString(1, runId)
                    s.executeQuery().use { rs ->
                        val out = mutableListOf<ReviewFindingRecord>()
                        while (rs.next()) out.add(rs.toReviewFinding())
                        out
                    }
                }
            }
        }

    override suspend fun publishedFindingsForIssue(issueId: String): List<ReviewFindingRecord> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                connection.prepareStatement(
                    "SELECT * FROM review_findings WHERE issue_id = ? AND published = 1 ORDER BY updated_at DESC"
                ).use { s ->
                    s.setString(1, issueId)
                    s.executeQuery().use { rs ->
                        val out = mutableListOf<ReviewFindingRecord>()
                        while (rs.next()) out.add(rs.toReviewFinding())
                        out
                    }
                }
            }
        }

    override suspend fun baseline(projectSlug: String?, windowDays: Int): ReviewBaseline =
        withContext(Dispatchers.IO) {
            val runsList = runs(projectSlug, limit = 100_000).filter {
                it.createdAt >= Instant.now().minusSeconds(windowDays.toLong() * 86_400).toString()
            }
            val runIds = runsList.map { it.runId }.toHashSet()
            val findings = lock.withLock {
                connection.prepareStatement("SELECT * FROM review_findings").use { s ->
                    s.executeQuery().use { rs ->
                        val out = mutableListOf<ReviewFindingRecord>()
                        while (rs.next()) out.add(rs.toReviewFinding())
                        out
                    }
                }
            }.filter { it.runId in runIds }

            val highEvidence = setOf("fixed", "likely_fixed", "discussed")
            val published = findings.filter { it.published }
            val heoCount = published.count { it.outcome in highEvidence }
            val humanLabeled = findings.filter { it.humanLabel != null }
            val fpCount = humanLabeled.count { it.humanLabel == "false_positive" }
            val totalTokens = runsList.sumOf { it.inputTokens + it.outputTokens }

            ReviewBaseline(
                windowDays = windowDays,
                totalRuns = runsList.size,
                reviewedRuns = runsList.count { it.eligibility == "reviewed" },
                skippedRuns = runsList.count { it.eligibility.startsWith("skipped") },
                fallbackRuns = runsList.count { it.parseStatus == "fallback" },
                totalFindings = findings.size,
                publishedFindings = published.size,
                highEvidenceOutcomes = heoCount,
                highEvidenceRate = if (published.isEmpty()) 0.0 else heoCount.toDouble() / published.size,
                humanLabeled = humanLabeled.size,
                falsePositives = fpCount,
                falsePositiveRate = if (humanLabeled.isEmpty()) 0.0 else fpCount.toDouble() / humanLabeled.size,
                totalTokens = totalTokens,
                tokensPerUsefulFinding = if (heoCount == 0) 0.0 else totalTokens.toDouble() / heoCount,
                findingsByCategory = published.groupingBy { it.category }.eachCount(),
                fpByCategory = humanLabeled.filter { it.humanLabel == "false_positive" }
                    .groupingBy { it.category }.eachCount()
            )
        }

    private fun ResultSet.toReviewRun() = ReviewRunRecord(
        runId = getString("run_id"),
        issueId = getString("issue_id"),
        issueIdentifier = getString("issue_identifier"),
        projectSlug = getString("project_slug"),
        attempt = getInt("attempt"),
        commitSha = getString("commit_sha"),
        prNumber = getInt("pr_number").let { if (wasNull()) null else it },
        model = getString("model"),
        promptVersion = getString("prompt_version"),
        riskTier = getString("risk_tier"),
        reviewMode = getString("review_mode"),
        eligibility = getString("eligibility"),
        parseStatus = getString("parse_status"),
        verdict = getString("verdict"),
        findingsTotal = getInt("findings_total"),
        findingsPublished = getInt("findings_published"),
        inputTokens = getLong("input_tokens"),
        outputTokens = getLong("output_tokens"),
        durationMs = getLong("duration_ms"),
        contextPackJson = getString("context_pack_json"),
        createdAt = getString("created_at")
    )

    private fun ResultSet.toReviewFinding() = ReviewFindingRecord(
        findingId = getString("finding_id"),
        runId = getString("run_id"),
        seq = getInt("seq"),
        specialist = getString("specialist"),
        category = getString("category"),
        severity = getString("severity"),
        confidence = getDouble("confidence").let { if (wasNull()) null else it },
        file = getString("file"),
        line = getInt("line").let { if (wasNull()) null else it },
        description = getString("description"),
        expectedAction = getString("expected_action"),
        evidence = getString("evidence"),
        published = getInt("published") == 1,
        dropReason = getString("drop_reason"),
        outcome = getString("outcome"),
        outcomeSource = getString("outcome_source"),
        humanLabel = getString("human_label"),
        issueId = getString("issue_id"),
        updatedAt = getString("updated_at")
    )
}
