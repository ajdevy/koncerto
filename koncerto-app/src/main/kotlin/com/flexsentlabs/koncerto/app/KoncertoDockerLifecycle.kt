package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.agent.DockerContainerManager
import com.flexsentlabs.koncerto.deploy.DockerLaunchCleaner
import com.flexsentlabs.koncerto.deploy.OrphanedContainerCleanupScheduler
import com.flexsentlabs.koncerto.deploy.TargetProjectDeployer
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.runBlocking

/**
 * Coordinates Docker resource cleanup at process launch, shutdown, and between runs.
 */
class KoncertoDockerLifecycle(
    private val launchCleaner: DockerLaunchCleaner,
    private val deployer: TargetProjectDeployer,
    private val orphanScheduler: OrphanedContainerCleanupScheduler,
    private val logger: StructuredLogger
) {
    suspend fun cleanOnLaunch() {
        logger.info("docker_lifecycle_launch_start", emptyMap())
        runCatching { deployer.cleanupOrphans() }.onFailure { e ->
            logger.warn("docker_lifecycle_orphan_cleanup_failed", mapOf("error" to (e.message ?: "unknown")))
        }
        runCatching { launchCleaner.cleanOnLaunch() }.onFailure { e ->
            logger.warn("docker_lifecycle_launch_cleanup_failed", mapOf("error" to (e.message ?: "unknown")))
        }
        runCatching { DockerContainerManager.pruneOldContainers(logger, olderThanHours = 1) }.onFailure { e ->
            logger.warn("docker_lifecycle_agent_prune_failed", mapOf("error" to (e.message ?: "unknown")))
        }
        logger.info("docker_lifecycle_launch_complete", emptyMap())
    }

    fun cleanOnShutdown() {
        logger.info("docker_lifecycle_shutdown_start", emptyMap())
        orphanScheduler.stop()
        runBlocking {
            runCatching { deployer.cleanupOrphans() }.onFailure { e ->
                logger.warn("docker_lifecycle_shutdown_orphan_cleanup_failed", mapOf("error" to (e.message ?: "unknown")))
            }
        }
        runCatching { DockerContainerManager.pruneOldContainers(logger, olderThanHours = 0) }.onFailure { e ->
            logger.warn("docker_lifecycle_shutdown_agent_prune_failed", mapOf("error" to (e.message ?: "unknown")))
        }
        logger.info("docker_lifecycle_shutdown_complete", emptyMap())
    }
}
