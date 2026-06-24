package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.deploy.DeployConfig
import com.flexsentlabs.koncerto.deploy.DemoFailureReporter
import com.flexsentlabs.koncerto.deploy.TargetProjectDeployer
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
    private val targetProjectDeployer: TargetProjectDeployer? = null,
    private val deployRepoFullName: String? = null,
    private val demoFailureReporter: DemoFailureReporter? = null
) {
    private val reviewStage: StageAgentConfig?
        get() = projectConfig.agent.stages["in review"]

    private var reviewSequence = 0

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
        val detailedReviewPath = workspace?.path?.resolve(".review-output-detailed")
        val reviewOutputPath = workspace?.path?.resolve(".review-output")
        if (reviewOutputPath != null && Files.exists(reviewOutputPath) && detailedReviewPath != null) {
            try {
                Files.copy(reviewOutputPath, detailedReviewPath, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
        }

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
            runtimeState.reviewAttempts.remove(issue.id)

            saveDemoScenario(issue, workspace)
            val deployUrl = deployTargetProject(issue, workspace)
            val demoUrl = onReviewPassed?.invoke(issue, deployUrl)
            postDetailedReviewAsPrComment(issue, workspace, reviewSequence, demoUrl)
            ReviewDecision.Pass(stage.onCompleteState)
        } else if (currentAttempt < maxAttempts) {
            logger.info(
                "review_failed_reroute", mapOf(
                    "issue_id" to issue.id,
                    "attempt" to currentAttempt.toString(),
                    "max_attempts" to maxAttempts.toString()
                )
            )
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
            ReviewDecision.Blocked
        }
    }

    private fun postDetailedReviewAsPrComment(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace?, sequence: Int, demoUrl: String? = null) {
        val ws = workspace ?: return
        val detailedPath = ws.path.resolve(".review-output-detailed")
        if (!Files.exists(detailedPath)) return
        val raw = try {
            Files.readString(detailedPath)
        } catch (_: Exception) { return }
        if (raw.isBlank()) return

        val lines = raw.lines().dropWhile { line ->
            line.isBlank() ||
                line.startsWith("Claude configuration file not found") ||
                line.startsWith("A backup file exists at:") ||
                line.startsWith("You can manually restore")
        }
        val startIdx = lines.indexOfFirst { it.startsWith("---") }
        val content = if (startIdx >= 0) {
            lines.drop(startIdx + 1).joinToString("\n").trim()
        } else {
            val firstVerdict = lines.indexOfFirst { it.trimStart().startsWith("✅") || it.trimStart().startsWith("❌") }
            if (firstVerdict >= 0) {
                lines.drop(firstVerdict).joinToString("\n").trim()
            } else {
                lines.joinToString("\n").trim()
            }
        }
        if (content.isBlank()) return

        val modelName = reviewStage?.model ?: "claude"
        val header = "### Claude Review #$sequence ($modelName)\n"
        val demoLink = if (!demoUrl.isNullOrBlank()) "\n---\n🎥 [Watch Demo Recording]($demoUrl)" else ""
        val body = header + content + demoLink

        try {
            val pb = ProcessBuilder("gh", "pr", "comment", "--body", body)
                .directory(ws.path.toFile())
                .redirectErrorStream(true)
            val proc = pb.start()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                logger.info("pr_review_comment_posted", mapOf(
                    "issue_id" to issue.id,
                    "issue_identifier" to issue.identifier
                ))
            } else {
                val err = proc.inputStream.bufferedReader().readText().take(200)
                logger.warn("pr_review_comment_failed", mapOf(
                    "issue_id" to issue.id,
                    "error" to err
                ))
            }
        } catch (e: Exception) {
            logger.warn("pr_review_comment_error", mapOf(
                "issue_id" to issue.id,
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    private fun saveDemoScenario(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace?) {
        val ws = workspace ?: return
        val detailedPath = ws.path.resolve(".review-output-detailed")
        if (!Files.exists(detailedPath)) return
        val raw = try { Files.readString(detailedPath) } catch (_: Exception) { return }
        if (raw.isBlank()) return

        val scenarioMatch = Regex("""demo_scenario:\s*\n(?:.*\n)*?(?=\n---|\n```|$)""").find(raw)
        val scenarioBlock = scenarioMatch?.value?.let { block ->
            val trimmed = block.trimEnd()
            "demo_scenario:\n" + trimmed.lines().drop(1).joinToString("\n")
        } ?: return

        val scenarioDir = java.nio.file.Paths.get("/tmp/koncerto-demo")
        try {
            java.nio.file.Files.createDirectories(scenarioDir)
            val targetPath = scenarioDir.resolve("${issue.id}-scenario.yaml")
            Files.writeString(targetPath, scenarioBlock)
            logger.info("demo_scenario_saved", mapOf(
                "issue_id" to issue.id,
                "path" to targetPath.toString()
            ))
        } catch (e: Exception) {
            logger.warn("demo_scenario_save_failed", mapOf(
                "issue_id" to issue.id,
                "error" to (e.message ?: "unknown")
            ))
        }
    }

    private suspend fun handleReviewExhaustion(issue: Issue, totalAttempts: Int) {
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

    private fun readReviewStatus(workspacePath: Path): Boolean {
        val statusFile = workspacePath.resolve(".review-status")
        return try {
            if (!Files.exists(statusFile)) return false
            // Retry once on empty content to tolerate slow filesystem flushes
            val content = Files.readString(statusFile).trim()
            if (content.isEmpty()) {
                Thread.sleep(100)
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

    private suspend fun deployTargetProject(issue: Issue, workspace: com.flexsentlabs.koncerto.workspace.Workspace?): String? {
        val deployer = targetProjectDeployer ?: return null
        val repoFullName = deployRepoFullName ?: return null
        val ws = workspace ?: return null
        if (!Files.isDirectory(ws.path)) return null

        logger.info("deploy_target_project_start", mapOf("issue_id" to issue.id, "repo" to repoFullName))
        val deployConfig = DeployConfig(
            repoFullName = repoFullName,
            prBranch = issue.identifier,
            baseBranch = "main",
            projectPath = ws.path
        )
        return try {
            val result = deployer.deploy(deployConfig)
            if (result.success) {
                logger.info("deploy_target_project_ok", mapOf("url" to result.url!!))
                result.url
            } else {
                val prNumber = resolvePrNumber(ws.path)
                logger.warn("deploy_target_project_failed", mapOf("reason" to (result.error ?: "unknown"), "logs" to (result.logs?.take(200) ?: "")))
                demoFailureReporter?.postFailure(prNumber ?: 0, repoFullName, result.error ?: "unknown", result.logs)
                null
            }
        } catch (e: Exception) {
            logger.warn("deploy_target_project_error", mapOf("issue_id" to issue.id), "error" to (e.message ?: "unknown"))
            null
        }
    }

    private fun resolvePrNumber(workspacePath: Path): Int? {
        return try {
            val pb = ProcessBuilder("gh", "pr", "view", "--json", "number")
                .directory(workspacePath.toFile())
                .redirectErrorStream(true)
                .start()
            val output = pb.inputStream.bufferedReader().readText()
            if (pb.waitFor() == 0) {
                val match = Regex(""""number":(\d+)""").find(output)
                match?.groupValues?.get(1)?.toIntOrNull()
            } else null
        } catch (_: Exception) { null }
    }

    private fun buildDefaultReviewPrompt(issue: Issue): String =
        "Review the code changes for issue ${issue.identifier}: ${issue.title}. " +
            "Check for correctness, edge cases, security issues, and adherence to best practices. " +
            "Write '❌ FAIL' if there are critical issues, otherwise indicate the review passed."

    sealed class ReviewDecision {
        data object NoReview : ReviewDecision()
        data class Pass(val transitionToState: String?) : ReviewDecision()
        data class RetryWithCoding(val rerouteToState: String?) : ReviewDecision()
        data object Blocked : ReviewDecision()
    }
}
