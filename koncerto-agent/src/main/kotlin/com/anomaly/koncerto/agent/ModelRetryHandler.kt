package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.config.ProjectConfig
import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.core.tracker.TrackerClient
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.notifications.CompositeNotifier
import com.anomaly.koncerto.notifications.NotificationEvent
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

class ModelRetryHandler(
    private val cycler: FreeModelCycler,
    private val projectConfig: ProjectConfig,
    private val linearClient: TrackerClient,
    private val notifier: CompositeNotifier,
    private val logger: StructuredLogger
) {
    private val workspaceRoot: Path = Paths.get(projectConfig.workspace.root)

    suspend fun <T> executeWithRetry(
        issueId: String,
        operation: suspend (String) -> Result<T, Exception>
    ): Result<T, Exception> {
        val backoffMs = listOf(1000L, 2000L, 4000L)
        var lastError: String? = null

        while (true) {
            val modelResult = cycler.nextModel()

            when (modelResult) {
                is Result.Failure<*> -> {
                    val exhausted = modelResult.error as? ModelExhaustedException
                    if (exhausted != null) {
                        return handleExhaustion(issueId, exhausted)
                    }
                }
                is Result.Success<*> -> {}
            }

            val model = when (modelResult) {
                is Result.Success -> modelResult.value
                is Result.Failure -> throw IllegalStateException("Unexpected failure in model selection")
            }
            logger.info("model_retry_attempt", mapOf(
                "issue_id" to issueId,
                "model" to model
            ))

            val result = operation(model)

            when (result) {
                is Result.Success<*> -> {
                    cycler.reportSuccess(model)
                    return result
                }
                is Result.Failure<*> -> {
                    val error = result.error
                    lastError = error.message
                    cycler.reportFailure(model, lastError)

                    val retries = cycler.getStatus()["retry_counts"] as? Map<String, Int> ?: emptyMap()
                    val modelRetries = retries[model] ?: 0

                    if (modelRetries >= 3) {
                        logger.warn("model_retries_exhausted", mapOf(
                            "issue_id" to issueId,
                            "model" to model,
                            "retries" to modelRetries.toString()
                        ))
                        continue
                    }

                    val delayMs = if (modelRetries < backoffMs.size) backoffMs[modelRetries] else backoffMs.last()
                    logger.info("model_retry_backoff", mapOf(
                        "issue_id" to issueId,
                        "model" to model,
                        "delay_ms" to delayMs.toString()
                    ))
                    delay(delayMs)
                }
            }
        }
    }

    private suspend fun <T> handleExhaustion(issueId: String, exhausted: ModelExhaustedException): Result<T, Exception> {
        logger.error("model_exhaustion_final", mapOf(
            "issue_id" to issueId,
            "models_tried" to exhausted.modelsTried.joinToString(","),
            "total_retries" to exhausted.totalRetries.toString()
        ))

        try {
            val blockedStateId = linearClient.resolveStateId(projectConfig.tracker.projectSlug, projectConfig.tracker.blockedState)
            if (blockedStateId != null) {
                linearClient.updateIssueState(issueId, blockedStateId)
                logger.info("linear_ticket_blocked", mapOf("issue_id" to issueId))
            } else {
                logger.warn("blocked_state_not_found", mapOf("blocked_state" to projectConfig.tracker.blockedState))
            }
        } catch (e: Exception) {
            logger.warn("linear_block_failed", mapOf("issue_id" to issueId, "error" to (e.message ?: "unknown")))
        }

        writeExhaustionStatusFile(issueId, exhausted)

        try {
            val issue = linearClient.fetchIssueById(issueId)
            if (issue != null) {
                notifier.send(NotificationEvent.AgentFailed(
                    projectSlug = projectConfig.tracker.projectSlug,
                    issueId = issueId,
                    issueIdentifier = issue.identifier,
                    title = issue.title,
                    error = "All free models exhausted after ${exhausted.totalRetries} retries"
                ))
                logger.info("exhaustion_notification_sent", mapOf("issue_id" to issueId))
            }
        } catch (e: Exception) {
            logger.warn("notification_send_failed", mapOf("issue_id" to issueId, "error" to (e.message ?: "unknown")))
        }

        return Result.Failure(exhausted)
    }

    private fun writeExhaustionStatusFile(issueId: String, exhausted: ModelExhaustedException) {
        val statusFile = workspaceRoot.resolve(".model-exhausted-$issueId")
        val json = buildJsonObject {
            put("issue_id", issueId)
            put("models_tried", JsonArray(exhausted.modelsTried.map { JsonPrimitive(it) }))
            put("total_retries", exhausted.totalRetries)
            put("timestamp", Instant.now().toString())
            put("reason", "All free models exhausted")
            put("last_error", exhausted.lastError)
        }
        try {
            Files.writeString(statusFile, json.toString())
            logger.info("exhaustion_status_written", mapOf("issue_id" to issueId, "file" to statusFile.toString()))
        } catch (e: Exception) {
            logger.warn("exhaustion_status_write_failed", mapOf("issue_id" to issueId, "error" to (e.message ?: "unknown")))
        }
    }
}
