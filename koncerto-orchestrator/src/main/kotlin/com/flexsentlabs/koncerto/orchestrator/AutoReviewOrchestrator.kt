package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.agent.AgentRunner
import com.flexsentlabs.koncerto.core.config.ProjectConfig
import com.flexsentlabs.koncerto.core.config.StageAgentConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.notifications.CompositeNotifier
import com.flexsentlabs.koncerto.notifications.NotificationEvent
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
    private val logger: StructuredLogger
) {
    private val reviewStage: StageAgentConfig?
        get() = projectConfig.agent.stages["in review"]

    suspend fun onCodingComplete(issue: Issue): ReviewDecision {
        val stage = reviewStage ?: return ReviewDecision.NoReview

        val maxAttempts = stage.maxReviewAttempts ?: 3
        val currentAttempt = (runtimeState.reviewAttempts[issue.id] ?: 0) + 1
        runtimeState.reviewAttempts[issue.id] = currentAttempt

        logger.info(
            "review_dispatching", mapOf(
                "issue_id" to issue.id,
                "issue_identifier" to issue.identifier,
                "attempt" to currentAttempt.toString(),
                "max_attempts" to maxAttempts.toString()
            )
        )

        val reviewPrompt = stage.prompt ?: buildDefaultReviewPrompt(issue)
        val reviewKind = stage.agentKind ?: "claude"
        val reviewCommand = stage.command

        agentRunner.run(
            issue = issue,
            attempt = currentAttempt,
            prompt = reviewPrompt,
            agentKindOverride = reviewKind,
            commandOverride = reviewCommand
        )

        val workspace = runCatching { workspaceManager.ensureWorkspace(issue.identifier) }.getOrNull()
        val passed = workspace?.let { readReviewStatus(it.path) } ?: false

        return if (passed) {
            logger.info("review_passed", mapOf("issue_id" to issue.id, "attempt" to currentAttempt.toString()))
            runtimeState.reviewAttempts.remove(issue.id)
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
