package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.errors.PatternErrorClassifier
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.deploy.DeployConfig
import com.flexsentlabs.koncerto.deploy.DeployResult
import com.flexsentlabs.koncerto.deploy.DemoFailureReporter
import com.flexsentlabs.koncerto.deploy.ProjectDeployer
import com.flexsentlabs.koncerto.logging.RollingTraceFiles
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
import com.flexsentlabs.koncerto.workflow.WorkflowCache
import com.flexsentlabs.koncerto.workspace.WorkspaceManager
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

data class GhProcessResult(val exitCode: Int, val output: String)

typealias GhProcessRunner = (command: List<String>, workDir: Path) -> GhProcessResult

private val defaultGhProcessRunner: GhProcessRunner = { command, workDir ->
    val pb = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
    val proc = pb.start()
    val output = proc.inputStream.bufferedReader().use { it.readText() }
    proc.waitFor()
    GhProcessResult(proc.exitValue(), output)
}

class AutoReviewOrchestrator(
    private val agentRunner: AgentRunner,
    private val workspaceManager: WorkspaceManager,
    private val linearClient: TrackerClient,
    private val projectConfig: ProjectConfig,
    private val projectSlug: String,
    private val runtimeState: RuntimeState,
    private val notifier: CompositeNotifier?,
    private val logger: StructuredLogger,
    private val workflowCache: WorkflowCache? = null,
    private val onReviewPassed: (suspend (Issue, targetUrl: String?) -> String?)? = null,
    private val targetProjectDeployer: ProjectDeployer? = null,
    private val deployRepoFullName: String? = null,
    private val demoFailureReporter: DemoFailureReporter? = null,
    private val demoScenarioGenerator: DemoScenarioGenerator? = null,
    private val ghProcessRunner: GhProcessRunner = defaultGhProcessRunner
) {
    private val reviewStage: StageAgentConfig?
        get() = projectConfig.agent.stages["in review"] ?: projectConfig.agent.stages["review"]

    private var reviewSequence = 0

    private companion object {
        // Max demo→fix recovery cycles per issue before the ticket is Blocked. Counts both
        // scenario repairs and code-fix escalations, so a persistently-unrecordable demo can't
        // loop indefinitely.
        const val MAX_DEMO_RECOVERY = 3
    }

    suspend fun onCodingComplete(issue: Issue): ReviewDecision {
        val stage = reviewStage ?: return ReviewDecision.NoReview

        val maxAttempts = stage.maxReviewAttempts ?: 3
        val currentAttempt = (runtimeState.reviewAttempts[issue.id] ?: 0) + 1
        runtimeState.reviewAttempts[issue.id] = currentAttempt
        reviewSequence++

        logger.info(
            "review_dispatching", mapOf(
                "issue_id" to issue.id,
                "issue_identifier" to issue.identifier,
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            )
        )

        // Backup the In Review stage's review output before auto-review overwrites it
        val workspace = runCatching { workspaceManager.ensureWorkspace(issue.identifier) }.getOrNull()
        backupReviewOutput(workspace)

        traceReviewStep(workspace, issue, "review_pass", "start", mapOf(
            "attempt" to currentAttempt.toString(),
            "max_attempts" to maxAttempts.toString()
        ))

        val rawReviewPrompt = stage.prompt ?: buildDefaultReviewPrompt(issue)
        val reviewPrompt = workflowCache?.resolvePrompt(rawReviewPrompt) ?: rawReviewPrompt
        val reviewKind = stage.agentKind ?: "claude"
        val reviewCommand = stage.command

        agentRunner.run(
            issue = issue,
            attempt = currentAttempt,
            prompt = reviewPrompt,
            agentKindOverride = reviewKind,
            commandOverride = reviewCommand
        )

        val passed = workspace?.let { readReviewStatus(it.path) } ?: false

        return if (passed) {
            logger.info("review_passed", mapOf("issue_id" to issue.id, "attempt" to currentAttempt.toString()))
            traceReviewStep(workspace, issue, "review_pass", "passed", mapOf("attempt" to currentAttempt.toString()))
            runtimeState.reviewAttempts.remove(issue.id)
            executePostReviewPipeline(issue, workspace, stage, currentAttempt)
        } else if (currentAttempt < maxAttempts) {
            logger.info(
                "review_failed_reroute", mapOf(
                    "issue_id" to issue.id,
                    "attempt" to currentAttempt.toString(),
                    "max_attempts" to maxAttempts.toString()
                )
            )
            workspace?.let { cleanupReviewFiles(it.path) }
            traceReviewStep(workspace, issue, "review_pass", "retry", mapOf(
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            ))
            ReviewDecision.RetryWithCoding(stage.onFailureState)
        } else {
            logger.warn(
                "review_max_attempts_exhausted", mapOf(
                    "issue_id" to issue.id,
                    "max_attempts" to maxAttempts.toString()
                )
            )
            runtimeState.reviewAttempts.remove(issue.id)
            handleReviewExhaustion(issue, currentAttempt)
            workspace?.let { cleanupReviewFiles(it.path) }
            traceReviewStep(workspace, issue, "review_pass", "blocked", mapOf(
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            ))
            ReviewDecision.Blocked
        }
    }

    suspend fun onReviewStageComplete(issue: Issue): ReviewDecision {
        val stage = reviewStage ?: return ReviewDecision.NoReview

        val workspace = runCatching { workspaceManager.ensureWorkspace(issue.identifier) }.getOrNull()
        backupReviewOutput(workspace)

        if (workspace != null && isSubscriptionLimitError(workspace.path)) {
            return handleSubscriptionLimit(issue, workspace)
        }

        val maxAttempts = stage.maxReviewAttempts ?: 3
        val currentAttempt = (runtimeState.reviewAttempts[issue.id] ?: 0) + 1
        runtimeState.reviewAttempts[issue.id] = currentAttempt
        reviewSequence++

        val passed = workspace?.let { readReviewStatus(it.path) } ?: false

        return if (passed) {
            logger.info("review_passed", mapOf("issue_id" to issue.id, "attempt" to currentAttempt.toString()))
            traceReviewStep(workspace, issue, "review_pass", "passed", mapOf("attempt" to currentAttempt.toString()))
            runtimeState.reviewAttempts.remove(issue.id)
            executePostReviewPipeline(issue, workspace, stage, currentAttempt)
        } else if (currentAttempt < maxAttempts) {
            logger.info(
                "review_failed_reroute", mapOf(
                    "issue_id" to issue.id,
                    "attempt" to currentAttempt.toString(),
                    "max_attempts" to maxAttempts.toString()
                )
            )
            workspace?.let { cleanupReviewFiles(it.path) }
            traceReviewStep(workspace, issue, "review_pass", "retry", mapOf(
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            ))
            ReviewDecision.RetryWithCoding(stage.onFailureState)
        } else {
            logger.warn(
                "review_max_attempts_exhausted", mapOf(
                    "issue_id" to issue.id,
                    "max_attempts" to maxAttempts.toString()
                )
            )
            runtimeState.reviewAttempts.remove(issue.id)
            handleReviewExhaustion(issue, currentAttempt)
            workspace?.let { cleanupReviewFiles(it.path) }
            traceReviewStep(workspace, issue, "review_pass", "blocked", mapOf(
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            ))
            ReviewDecision.Blocked
        }
    }

    private fun cleanupReviewFiles(workspacePath: Path) {
        listOf(
            ".review-status",
            ".review-output",
            ".review-output-detailed",
            ".review-attempt",
        ).forEach { name ->
            try {
                Files.deleteIfExists(workspacePath.resolve(name))
            } catch (e: Exception) {
                logger.warn("review_cleanup_failed", mapOf("file" to name, "error" to (e.message ?: "unknown")))
            }
        }
    }

    private fun backupReviewOutput(workspace: com.flexsentlabs.koncerto.workspace.Workspace?) {
        val detailedReviewPath = workspace?.path?.resolve(".review-output-detailed")
        val reviewOutputPath = workspace?.path?.resolve(".review-output")
        if (reviewOutputPath != null && Files.exists(reviewOutputPath) && detailedReviewPath != null) {
            try {
                Files.copy(reviewOutputPath, detailedReviewPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                logger.warn("review_backup_copy_failed", mapOf("error" to (e.message ?: "unknown")))
            }
        }
    }

    private suspend fun executePostReviewPipeline(
        issue: Issue,
        workspace: com.flexsentlabs.koncerto.workspace.Workspace?,
        stage: StageAgentConfig,
        currentAttempt: Int
    ): ReviewDecision {
        val scenarioGenerationAttempted = demoScenarioGenerator != null
        val scenarioPath = runCatching {
            workspace?.let { demoScenarioGenerator?.generate(issue, it) }
        }.onFailure { e ->
            logger.warn("demo_scenario_generator_failed", mapOf(
                "issue_id" to issue.id,
                "error" to (e.message ?: "unknown")
            ))
            traceReviewStep(workspace, issue, "demo_scenario", "failed", mapOf(
                "error" to (e.message ?: "unknown")
            ))
        }.getOrNull()
        if (scenarioPath != null) {
            traceReviewStep(workspace, issue, "demo_scenario", "saved", mapOf("path" to scenarioPath))
        } else if (scenarioGenerationAttempted) {
            // A scenario generator IS configured but could not produce a scenario even after
            // its own internal fallback/retry passes — never fall back to recording without one
            // (a scenario-less recording just shows the app idling, which isn't a useful demo).
            // Treat this the same as a demo recording failure: block for a human to notice
            // rather than silently shipping a low-value recording.
            logger.warn("demo_scenario_generation_exhausted", mapOf("issue_id" to issue.id))
            traceReviewStep(workspace, issue, "demo_scenario", "exhausted")
            workspace?.let { cleanupReviewFiles(it.path) }
            handleDemoRecordingFailure(issue, "scenario generation failed after exhausting all fallback models")
            traceReviewStep(workspace, issue, "review_pass", "blocked", mapOf(
                "attempt" to currentAttempt.toString(),
                "reason" to "no_scenario"
            ))
            return ReviewDecision.Blocked
        } else {
            traceReviewStep(workspace, issue, "demo_scenario", "skipped")
        }

        // Reliable missing-secret gate: the dispatch-start preflight can be a no-op on a brand-new
        // ticket (the repo isn't checked out yet), but by now the workspace is fully populated, so we
        // can detect required secrets accurately. A missing operator secret is NOT agent-fixable, so
        // block directly here rather than feeding it into the self-healing recovery loop.
        if (workspace != null) {
            val missing = runCatching {
                val required = com.flexsentlabs.koncerto.deploy.SecretRequirementDetector().detect(workspace.path)
                val provided = com.flexsentlabs.koncerto.deploy.SecretsFile.load(projectConfig.demoSecretsFile).keys
                (required - provided).sorted()
            }.getOrDefault(emptyList())
            if (missing.isNotEmpty()) {
                logger.warn("demo_blocked_missing_secrets", mapOf(
                    "issue_id" to issue.id, "missing" to missing.joinToString(",")))
                cleanupReviewFiles(workspace.path)
                handleDemoRecordingFailure(issue,
                    "required secret(s) not configured: ${missing.joinToString(", ")}. " +
                        "Add them to this project's demo secrets file, then unblock.")
                traceReviewStep(workspace, issue, "review_pass", "blocked", mapOf(
                    "attempt" to currentAttempt.toString(), "reason" to "missing_secrets"))
                return ReviewDecision.Blocked
            }
        }

        return runDemoWithRecovery(issue, workspace, stage, currentAttempt, scenarioPath)
    }

    /**
     * Records the demo with bounded self-healing. On a recording failure it first tries a cheap
     * scenario repair and re-records against the SAME deployment (no re-review). If a scenario
     * repair cannot be produced, it escalates to a target-code fix by writing the failure as
     * coding feedback and returning [ReviewDecision.RetryWithCoding], so the normal coding+review
     * loop re-runs and — on a fresh review pass — re-enters here. A per-issue counter caps the
     * whole thing at [MAX_DEMO_RECOVERY] cycles, after which the ticket is Blocked with a comment.
     */
    private suspend fun runDemoWithRecovery(
        issue: Issue,
        workspace: com.flexsentlabs.koncerto.workspace.Workspace?,
        stage: StageAgentConfig,
        currentAttempt: Int,
        initialScenarioPath: String?
    ): ReviewDecision {
        val deployResult = runCatching { deployTargetProject(issue, workspace) }
            .onFailure { e ->
                logger.warn("deploy_target_project_unhandled_error", mapOf(
                    "issue_id" to issue.id, "error" to (e.message ?: "unknown")))
                traceReviewStep(workspace, issue, "deploy_target_project", "failed", mapOf(
                    "error" to (e.message ?: "unknown")))
            }.getOrNull()
        val deployUrl = deployResult?.url
        // The recorder runs in its own Docker container, so it must address the target by its
        // internal-network URL (http://<containerName>:<port>) — localhost:hostPort only
        // resolves on the host, not from inside another container. deployUrl (host-facing) stays
        // as-is for tracing and the scenario-repair "did a deploy happen at all" gate below.
        val recordingUrl = deployResult?.internalUrl
        if (deployResult != null) {
            traceReviewStep(workspace, issue, "deploy_target_project", if (deployResult.success) "ok" else "failed", mapOf(
                "url" to (deployUrl ?: ""), "error" to (deployResult.error ?: "")))
        }

        while (true) {
            var demoRecordingError: String? = null
            val demoUrl = runCatching { onReviewPassed?.invoke(issue, recordingUrl) }
                .onFailure { e ->
                    demoRecordingError = e.message ?: "unknown"
                    logger.warn("demo_recording_callback_failed", mapOf(
                        "issue_id" to issue.id, "error" to (e.message ?: "unknown")))
                    traceReviewStep(workspace, issue, "demo_recording", "failed", mapOf(
                        "error" to (e.message ?: "unknown")))
                }.getOrNull()

            // Success (or nothing to record) → post comment, clean up, pass.
            if (demoUrl != null || demoRecordingError == null) {
                if (demoUrl != null) traceReviewStep(workspace, issue, "demo_recording", "ok", mapOf("url" to demoUrl))
                else traceReviewStep(workspace, issue, "demo_recording", "skipped")
                runCatching { postDetailedReviewAsPrComment(issue, workspace, reviewSequence, demoUrl) }
                    .onFailure { e ->
                        logger.warn("review_comment_pipeline_failed", mapOf(
                            "issue_id" to issue.id, "error" to (e.message ?: "unknown")))
                        traceReviewStep(workspace, issue, "pr_comment", "failed", mapOf("error" to (e.message ?: "unknown")))
                    }
                traceReviewStep(workspace, issue, "pr_comment", "attempted", mapOf("demo_url" to (demoUrl ?: "")))
                cleanupDeployBestEffort(issue, workspace, deployResult)
                workspace?.let { cleanupReviewFiles(it.path) }
                // Clear the recovery counter on success (mirrors reviewAttempts) so a later demo for
                // the same issue starts with a fresh budget instead of inheriting a stale count.
                runtimeState.demoRecoveryAttempts.remove(issue.id)
                traceReviewStep(workspace, issue, "review_pass", "complete", mapOf(
                    "attempt" to currentAttempt.toString(), "demo_url" to (demoUrl ?: ""), "deploy_url" to (deployUrl ?: "")))
                return ReviewDecision.Pass(stage.onCompleteState)
            }

            // Recording failed — count this recovery cycle.
            val cycle = (runtimeState.demoRecoveryAttempts[issue.id] ?: 0) + 1
            runtimeState.demoRecoveryAttempts[issue.id] = cycle
            logger.info("demo_recovery_cycle", mapOf(
                "issue_id" to issue.id, "cycle" to cycle.toString(), "error" to (demoRecordingError ?: "unknown")))

            if (cycle >= MAX_DEMO_RECOVERY) {
                cleanupDeployBestEffort(issue, workspace, deployResult)
                workspace?.let { cleanupReviewFiles(it.path) }
                handleDemoRecordingFailure(issue,
                    "demo could not be produced after $cycle recovery cycles: ${demoRecordingError}")
                // Reset so that if an operator later unblocks and re-dispatches this issue, it gets a
                // fresh set of recovery cycles instead of being re-blocked on the very first failure.
                runtimeState.demoRecoveryAttempts.remove(issue.id)
                traceReviewStep(workspace, issue, "review_pass", "blocked", mapOf(
                    "attempt" to currentAttempt.toString(), "cycles" to cycle.toString(), "demo_error" to demoRecordingError!!))
                return ReviewDecision.Blocked
            }

            // Scenario-first repair: re-record against the SAME deployment, no re-review.
            val priorScenario = initialScenarioPath?.let { runCatching { java.io.File(it).readText() }.getOrNull() }
            val generator = demoScenarioGenerator
            val repaired = if (workspace != null && generator != null && priorScenario != null && deployUrl != null) {
                runCatching { generator.repair(issue, workspace, priorScenario, demoRecordingError!!) }.getOrNull()
            } else null
            if (repaired != null) {
                traceReviewStep(workspace, issue, "demo_scenario", "repaired", mapOf("cycle" to cycle.toString()))
                continue
            }

            // Scenario repair unavailable/ineffective → escalate to a target code fix + re-review.
            cleanupDeployBestEffort(issue, workspace, deployResult)
            workspace?.let { writeDemoFixRequest(it.path, demoRecordingError!!) }
            traceReviewStep(workspace, issue, "demo_recovery", "escalated_to_code_fix", mapOf("cycle" to cycle.toString()))
            logger.info("demo_recovery_code_fix", mapOf("issue_id" to issue.id, "cycle" to cycle.toString()))
            return ReviewDecision.RetryWithCoding(stage.onFailureState)
        }
    }

    private suspend fun cleanupDeployBestEffort(
        issue: Issue,
        workspace: com.flexsentlabs.koncerto.workspace.Workspace?,
        deployResult: DeployResult?
    ) {
        if (deployResult == null) return
        runCatching { cleanupDemoDeploy(issue, workspace, deployResult) }.onFailure { e ->
            logger.warn("demo_cleanup_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
            traceReviewStep(workspace, issue, "deploy_cleanup", "failed", mapOf("error" to (e.message ?: "unknown")))
        }
        traceReviewStep(workspace, issue, "deploy_cleanup", "attempted")
    }

    /** Records the demo failure as coding feedback so a re-dispatched fix agent knows what to repair. */
    private fun writeDemoFixRequest(workspacePath: Path, failureReason: String) {
        runCatching {
            workspacePath.resolve(".demo-fix-request").toFile().writeText(
                "The demo recording failed and scenario repair did not resolve it. Fix the underlying " +
                    "issue in the app so the demo flow works, then the demo will be re-recorded.\n\n" +
                    "Failure detail:\n$failureReason\n")
        }.onFailure { e ->
            logger.warn("demo_fix_request_write_failed", mapOf("error" to (e.message ?: "unknown")))
        }
    }

    private suspend fun handleDemoRecordingFailure(issue: Issue, errorMessage: String) {
        try {
            linearClient.createComment(issue.id, "Blocked: demo recording failed after review passed: $errorMessage")
        } catch (e: Exception) {
            logger.warn("demo_blocked_comment_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }

        try {
            val blockedStateId = linearClient.resolveStateId(projectSlug, projectConfig.tracker.blockedState)
            if (blockedStateId != null) {
                linearClient.updateIssueState(issue.id, blockedStateId)
                logger.info("demo_recording_ticket_blocked", mapOf("issue_id" to issue.id))
            } else {
                logger.warn("blocked_state_not_found", mapOf("blocked_state" to projectConfig.tracker.blockedState))
            }
        } catch (e: Exception) {
            logger.warn("demo_recording_block_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }

        try {
            notifier?.send(
                NotificationEvent.AgentFailed(
                    projectSlug = projectSlug,
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    title = issue.title,
                    error = "Demo recording failed after review passed: $errorMessage"
                )
            )
        } catch (e: Exception) {
            logger.warn("demo_recording_notification_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }
    }

    private fun postDetailedReviewAsPrComment(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace?, sequence: Int, demoUrl: String? = null) {
        val ws = workspace ?: return

        val detailedPath = ws.path.resolve(".review-output-detailed")
        val content: String = if (Files.exists(detailedPath)) {
            val raw = try { Files.readString(detailedPath) } catch (_: Exception) { "" }
            val lines = raw.lines().dropWhile { line ->
                line.isBlank() ||
                    line.startsWith("Claude configuration file not found") ||
                    line.startsWith("A backup file exists at:") ||
                    line.startsWith("You can manually restore")
            }
            val startIdx = lines.indexOfFirst { it.startsWith("---") }
            if (startIdx >= 0) {
                lines.drop(startIdx + 1).joinToString("\n").trim()
            } else {
                val firstVerdict = lines.indexOfFirst { it.trimStart().startsWith("✅") || it.trimStart().startsWith("❌") }
                if (firstVerdict >= 0) lines.drop(firstVerdict).joinToString("\n").trim()
                else lines.joinToString("\n").trim()
            }
        } else ""

        // Always post if we have a demo URL, even with no review content
        if (content.isBlank() && demoUrl.isNullOrBlank()) return

        val modelName = reviewStage?.model ?: "claude"
        val header = "### 🤖 Claude Review #$sequence · $modelName\n"
        val demoLink = if (!demoUrl.isNullOrBlank()) "\n---\n🎥 [Watch Demo Recording]($demoUrl)" else ""
        val body = if (content.isBlank()) header + demoLink.trimStart('\n') else header + content + demoLink

        logger.info("pr_comment_debug", mapOf(
            "issue_id" to issue.id,
            "body_length" to body.length.toString(),
            "demo_url" to (demoUrl ?: "null"),
            "has_content" to content.isNotBlank().toString()
        ))

        val repo = resolveRepoFullName(ws.path) ?: deployRepoFullName ?: run {
            logger.warn("pr_comment_no_repo", mapOf("issue_id" to issue.id))
            traceReviewStep(workspace, issue, "pr_comment", "no_repo")
            return
        }
        val branch = resolveCurrentBranch(ws.path) ?: run {
            logger.warn("pr_comment_no_branch", mapOf("issue_id" to issue.id))
            traceReviewStep(workspace, issue, "pr_comment", "no_branch", mapOf("repo" to repo))
            return
        }
        val prNumber = resolvePrNumber(ws.path)
        try {
            val bodyFile = ws.path.resolve(".review-body.txt")
            bodyFile.toFile().writeText(body)
            try {
                val result = ghProcessRunner(
                    listOf("gh", "pr", "comment", branch, "--repo", repo, "--body-file", bodyFile.toString()),
                    ws.path
                )
                if (result.exitCode == 0) {
                    val commentUrl = result.output.trim().ifBlank { "(no url)" }
                    logger.info("pr_review_comment_posted", mapOf(
                        "issue_id" to issue.id,
                        "issue_identifier" to issue.identifier,
                        "comment_url" to commentUrl
                    ))
                    traceReviewStep(workspace, issue, "pr_comment", "posted", mapOf(
                        "repo" to repo,
                        "pr_number" to (prNumber?.toString() ?: ""),
                        "branch" to branch,
                        "comment_url" to commentUrl
                    ))
                } else {
                    val err = result.output.take(200)
                    logger.warn("pr_review_comment_failed", mapOf(
                        "issue_id" to issue.id,
                        "error" to err
                    ))
                    traceReviewStep(workspace, issue, "pr_comment", "failed", mapOf(
                        "repo" to repo,
                        "pr_number" to (prNumber?.toString() ?: ""),
                        "branch" to branch,
                        "error" to err
                    ))
                }
            } finally {
                try { Files.deleteIfExists(bodyFile) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger.warn("pr_review_comment_error", mapOf(
                "issue_id" to issue.id,
                "error" to (e.message ?: "unknown")
            ))
            traceReviewStep(workspace, issue, "pr_comment", "error", mapOf(
                "repo" to repo,
                "pr_number" to (prNumber?.toString() ?: ""),
                "branch" to branch,
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    private suspend fun handleReviewExhaustion(issue: Issue, totalAttempts: Int) {
        try {
            linearClient.createComment(issue.id, "Blocked: review gate failed after $totalAttempts attempt(s)")
        } catch (e: Exception) {
            logger.warn("blocked_comment_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }

        try {
            val blockedStateId = linearClient.resolveStateId(projectSlug, projectConfig.tracker.blockedState)
            if (blockedStateId != null) {
                linearClient.updateIssueState(issue.id, blockedStateId)
                logger.info("review_exhaustion_ticket_blocked", mapOf("issue_id" to issue.id))
            } else {
                logger.warn("blocked_state_not_found", mapOf("blocked_state" to projectConfig.tracker.blockedState))
            }
        } catch (e: Exception) {
            logger.warn("review_block_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }

        writeReviewExhaustionFile(issue, totalAttempts)

        try {
            notifier?.send(
                NotificationEvent.AgentFailed(
                    projectSlug = projectSlug,
                    issueId = issue.id,
                    issueIdentifier = issue.identifier,
                    title = issue.title,
                    error = "Review gate failed after $totalAttempts attempts"
                )
            )
        } catch (e: Exception) {
            logger.warn("review_notification_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }
    }

    private fun isSubscriptionLimitError(workspacePath: Path): Boolean {
        val outputFile = workspacePath.resolve(".review-output")
        if (!Files.exists(outputFile)) return false
        val content = runCatching { Files.readString(outputFile) }.getOrNull() ?: return false
        // "rate limit" alone is deliberately not checked here — it's a generic enough phrase
        // that a passing review discussing the REVIEWED APP's own rate-limiting code (e.g. an
        // auth/API review, which commonly flags this) false-positives as Claude's own usage
        // limit being hit, silently discarding a real pass/fail verdict on every such review.
        return content.contains("monthly usage limit") ||
            content.contains("subscription limit") ||
            PatternErrorClassifier.SUBSCRIPTION_USAGE_PATTERN.containsMatchIn(content)
    }

    private suspend fun handleSubscriptionLimit(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace): ReviewDecision {
        val postedFile = workspace.path.resolve(".subscription-limit-posted")
        if (!Files.exists(postedFile)) {
            try {
                linearClient.createComment(
                    issue.id,
                    "Claude subscription limit reached. The review will retry automatically once the limit resets. No action needed."
                )
                Files.writeString(postedFile, Instant.now().toString())
            } catch (e: Exception) {
                logger.warn("subscription_limit_comment_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
            }
        }
        // Register a limit pause so the dispatcher excludes this issue from candidates until the
        // resume window passes, then re-dispatches it once — the same mechanism the agent-run
        // limit path uses. Previously this returned RetryWithCoding(null), which left the issue
        // in the review state with no reroute, so it was re-dispatched (and re-hit the same limit)
        // on every poll cycle — a tight loop that burned the agent against an exhausted quota.
        val resumeAtMs = System.currentTimeMillis() + projectConfig.agent.limitPause.claudeDefaultResumeMs
        runtimeState.limitPauses[issue.id] = LimitPauseEntry(
            issueId = issue.id,
            identifier = issue.identifier,
            stageName = "in review",
            agentKind = "claude",
            provider = "claude",
            error = "review_subscription_limit",
            resumeAtMs = resumeAtMs
        )
        logger.info("review_subscription_limit_paused", mapOf(
            "issue_id" to issue.id,
            "resume_at_ms" to resumeAtMs.toString()
        ))
        traceReviewStep(workspace, issue, "review_subscription_limit", "paused", mapOf(
            "posted" to Files.exists(postedFile).toString(),
            "resume_at_ms" to resumeAtMs.toString()
        ))
        return ReviewDecision.Blocked
    }

    private suspend fun readReviewStatus(workspacePath: Path): Boolean {
        val statusFile = workspacePath.resolve(".review-status")
        return try {
            if (!Files.exists(statusFile)) return false
            val content = Files.readString(statusFile).trim()
            if (content.isEmpty()) {
                kotlinx.coroutines.delay(100)
                Files.readString(statusFile).trim().lowercase() == "pass"
            } else {
                content.lowercase() == "pass"
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun writeReviewExhaustionFile(issue: Issue, totalAttempts: Int) {
        val workspace = runCatching { workspaceManager.ensureWorkspace(issue.identifier) }.getOrNull() ?: return
        val statusFile = workspace.path.resolve(".review-exhausted")
        val tmpFile = workspace.path.resolve(".review-exhausted.tmp")
        val json = buildJsonObject {
            put("issue_id", issue.id)
            put("issue_identifier", issue.identifier)
            put("total_attempts", totalAttempts)
            put("timestamp", Instant.now().toString())
            put("reason", "Review gate failed after $totalAttempts attempts")
        }
        try {
            Files.writeString(tmpFile, json.toString())
            Files.move(tmpFile, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            logger.warn("review_exhaustion_file_write_failed", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
        }
    }

    private suspend fun deployTargetProject(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace?): DeployResult? {
        val deployer = targetProjectDeployer ?: return null
        val ws = workspace ?: return null
        val repoFullName = resolveRepoFullName(ws.path) ?: deployRepoFullName ?: return null
        if (!Files.isDirectory(ws.path)) return null

        logger.info("deploy_target_project_start", mapOf("issue_id" to issue.id, "repo" to repoFullName))
        val secrets = com.flexsentlabs.koncerto.deploy.SecretsFile.load(projectConfig.demoSecretsFile)
        if (secrets.isEmpty()) {
            logger.info("demo_secrets_none", mapOf("issue_id" to issue.id))
        }
        val deployConfig = DeployConfig(
            repoFullName = repoFullName,
            prBranch = issue.identifier,
            baseBranch = "main",
            projectPath = ws.path,
            envVars = secrets,
            postDeployCommand = projectConfig.demoPostDeployCommand
        )
        return try {
            val result = deployer.deploy(deployConfig)
            if (result.success) {
                logger.info("deploy_target_project_ok", mapOf("url" to (result.url ?: "unknown")))
                result
            } else {
                val prNumber = resolvePrNumber(ws.path)
                logger.warn("deploy_target_project_failed", mapOf("reason" to (result.error ?: "unknown"), "logs" to (result.logs?.take(200) ?: "")))
                demoFailureReporter?.postFailure(prNumber ?: 0, repoFullName, result.error ?: "unknown", result.logs)
                runCatching { deployer.cleanup(deployConfig) }.onFailure { e ->
                    logger.warn("deploy_target_project_cleanup_failed", mapOf(
                        "issue_id" to issue.id,
                        "error" to (e.message ?: "unknown")
                    ))
                }
                null
            }
        } catch (e: Exception) {
            logger.warn("deploy_target_project_error", mapOf("issue_id" to issue.id, "error" to (e.message ?: "unknown")))
            runCatching { deployer.cleanup(deployConfig) }.onFailure { cleanupError ->
                logger.warn("deploy_target_project_cleanup_failed", mapOf(
                    "issue_id" to issue.id,
                    "error" to (cleanupError.message ?: "unknown")
                ))
            }
            null
        }
    }

    private suspend fun cleanupDemoDeploy(
        issue: Issue,
        workspace: com.flexsentlabs.koncerto.workspace.Workspace?,
        deployResult: DeployResult
    ) {
        val deployer = targetProjectDeployer ?: return
        val ws = workspace ?: return
        val repoFullName = resolveRepoFullName(ws.path) ?: deployRepoFullName ?: return
        val deployConfig = DeployConfig(
            repoFullName = repoFullName,
            prBranch = issue.identifier,
            baseBranch = "main",
            projectPath = ws.path
        )
        deployer.cleanup(deployConfig)
    }

    private fun resolvePrNumber(workspacePath: Path): Int? {
        val repo = resolveRepoFullName(workspacePath) ?: return null
        val branch = resolveCurrentBranch(workspacePath) ?: return null
        return try {
            val viewResult = ghProcessRunner(
                listOf("gh", "pr", "view", branch, "--repo", repo, "--json", "number"),
                workspacePath
            )
            parsePrNumber(viewResult.output).takeIf { viewResult.exitCode == 0 }
                ?: run {
                    val listResult = ghProcessRunner(
                        listOf("gh", "pr", "list", "--repo", repo, "--head", branch, "--json", "number"),
                        workspacePath
                    )
                    parsePrNumber(listResult.output).takeIf { listResult.exitCode == 0 }
                }
        } catch (_: Exception) { null }
    }

    private fun parsePrNumber(output: String): Int? {
        val objectMatch = Regex(""""number":\s*(\d+)""").find(output)
        if (objectMatch != null) return objectMatch.groupValues[1].toIntOrNull()
        val listMatch = Regex("""\[\s*\{\s*"number":\s*(\d+)""").find(output)
        return listMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun resolveCurrentBranch(workspacePath: Path): String? {
        return try {
            val head = readGitHead(workspacePath) ?: return null
            if (head.startsWith("ref: refs/heads/")) head.removePrefix("ref: refs/heads/").trim()
            else null // detached HEAD
        } catch (_: Exception) { null }
    }

    private fun readGitHead(workspacePath: Path): String? {
        val gitDir = workspacePath.resolve(".git")
        return when {
            java.nio.file.Files.isDirectory(gitDir) -> {
                val headFile = gitDir.resolve("HEAD")
                if (java.nio.file.Files.exists(headFile)) java.nio.file.Files.readString(headFile).trim() else null
            }
            java.nio.file.Files.isRegularFile(gitDir) -> {
                val gitdirLine = java.nio.file.Files.readString(gitDir).trim()
                val gitdirPath = gitdirLine.removePrefix("gitdir:").trim()
                val headFile = java.nio.file.Path.of(gitdirPath).resolve("HEAD")
                if (java.nio.file.Files.exists(headFile)) java.nio.file.Files.readString(headFile).trim() else null
            }
            else -> null
        }
    }

    private fun buildDefaultReviewPrompt(issue: Issue): String =
        "Review the code changes for issue ${issue.identifier}: ${issue.title}. " +
            "Check for correctness, edge cases, security issues, and adherence to best practices. " +
            "Write '❌ FAIL' if there are critical issues, otherwise indicate the review passed."

    private fun resolveRepoFullName(workspacePath: Path): String? {
        val gitConfigPath = resolveGitConfigPath(workspacePath) ?: return null
        return try {
            val content = Files.readString(gitConfigPath)
            val originIdx = content.indexOf("[remote \"origin\"]")
            if (originIdx < 0) return null
            val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?\s*$""")
                .find(content, originIdx)
            match?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun resolveGitConfigPath(workspacePath: Path): Path? {
        val directConfig = workspacePath.resolve(".git/config")
        if (Files.exists(directConfig)) return directConfig
        val gitFile = workspacePath.resolve(".git")
        if (!Files.exists(gitFile) || !Files.isRegularFile(gitFile)) return null
        return try {
            val gitDirLine = Files.readString(gitFile).trim()
            val prefix = "gitdir: "
            if (!gitDirLine.startsWith(prefix)) return null
            val gitDir = Path.of(gitDirLine.removePrefix(prefix).trim())
            gitDir.resolve("config")
        } catch (_: Exception) {
            null
        }
    }

    private fun traceReviewStep(
        workspace: com.flexsentlabs.koncerto.workspace.Workspace?,
        issue: Issue,
        step: String,
        status: String,
        details: Map<String, String> = emptyMap()
    ) {
        val ws = workspace ?: return
        val traceDir = ws.path.resolve(".koncerto")
        try {
            val payload = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("issue_id", issue.id)
                put("issue_identifier", issue.identifier)
                put("step", step)
                put("status", status)
                details.forEach { (key, value) -> put(key, value) }
            }
            RollingTraceFiles.append(traceDir, "review-trace", payload.toString())
        } catch (e: Exception) {
            logger.warn("review_trace_write_failed", mapOf(
                "issue_id" to issue.id,
                "step" to step,
                "status" to status,
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    sealed class ReviewDecision {
        data object NoReview : ReviewDecision()
        data class Pass(val transitionToState: String?) : ReviewDecision()
        data class RetryWithCoding(val rerouteToState: String?) : ReviewDecision()
        data object Blocked : ReviewDecision()
    }
}
