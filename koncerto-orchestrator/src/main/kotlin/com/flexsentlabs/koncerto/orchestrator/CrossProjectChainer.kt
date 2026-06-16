package com.flexsentlabs.koncerto.orchestrator

import com.flexsentlabs.koncerto.core.config.CrossProjectFollowUpConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.model.Issue
import com.flexsentlabs.koncerto.core.tracker.TrackerClient
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.util.concurrent.ConcurrentHashMap

interface CrossProjectChainer {
    suspend fun createFollowUp(sourceIssue: Issue, followUpConfig: CrossProjectFollowUpConfig, projectSlug: String)
}

class DefaultCrossProjectChainer(
    private val config: ServiceConfig,
    private val linearClientProvider: (projectSlug: String) -> TrackerClient?,
    private val logger: StructuredLogger,
    private val maxDepth: Int = 3
) : CrossProjectChainer {

    private val chainDepth = ConcurrentHashMap<String, Int>()
    private val processedChains = ConcurrentHashMap.newKeySet<String>()

    override suspend fun createFollowUp(sourceIssue: Issue, followUpConfig: CrossProjectFollowUpConfig, projectSlug: String) {
        try {
            val targetSlug = followUpConfig.targetProjectSlug
            val chainKey = "${sourceIssue.id}:$targetSlug"
            if (!processedChains.add(chainKey)) {
                logger.info("chain_cycle_detected", mapOf(
                    "source_issue_id" to sourceIssue.id,
                    "target_slug" to targetSlug
                ))
                return
            }

            val currentDepth = chainDepth.merge(chainKey, 1, Int::plus) ?: 1
            if (currentDepth > maxDepth) {
                logger.info("chain_max_depth_reached", mapOf(
                    "source_issue_id" to sourceIssue.id,
                    "target_slug" to targetSlug,
                    "depth" to currentDepth
                ))
                return
            }

            val targetClient = linearClientProvider(targetSlug)
            if (targetClient == null) {
                logger.warn("chain_target_client_not_found", mapOf(
                    "source_issue_id" to sourceIssue.id,
                    "target_slug" to targetSlug
                ))
                return
            }

            val renderedTitle = followUpConfig.titleTemplate
                .replace("{sourceId}", sourceIssue.identifier)
                .replace("{sourceTitle}", sourceIssue.title)
                .replace("{sourceProject}", projectSlug)

            val renderedDescription = followUpConfig.descriptionTemplate
                ?.replace("{sourceId}", sourceIssue.identifier)
                ?.replace("{sourceTitle}", sourceIssue.title)
                ?.replace("{sourceProject}", projectSlug)

            val created = targetClient.createIssue(
                projectSlug = targetSlug,
                title = renderedTitle,
                state = "Todo",
                description = renderedDescription,
                labels = emptyList()
            )

            if (created == null) {
                logger.warn("chain_issue_creation_failed", mapOf(
                    "source_issue_id" to sourceIssue.id,
                    "target_slug" to targetSlug
                ))
                return
            }

            val linkType = followUpConfig.linkType
            if (linkType != null && linkType.isNotBlank()) {
                val linked = targetClient.createLink(sourceIssue.id, created.id, linkType)
                if (!linked) {
                    logger.warn("chain_link_failed", mapOf(
                        "source" to sourceIssue.id,
                        "target" to created.id,
                        "link_type" to linkType
                    ))
                }
            }

            logger.info("chain_follow_up_created", mapOf(
                "source_issue_id" to sourceIssue.id,
                "source_identifier" to sourceIssue.identifier,
                "target_slug" to targetSlug,
                "follow_up_id" to created.id,
                "depth" to currentDepth
            ))
        } catch (e: Exception) {
            logger.warn("chain_creation_failed", mapOf(
                "source_issue_id" to sourceIssue.id,
                "config_target" to followUpConfig.targetProjectSlug
            ), "error" to (e.message ?: "unknown"))
        }
    }
}
