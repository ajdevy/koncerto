package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OrphanedContainerCleanupScheduler(
    private val deployer: TargetProjectDeployer,
    private val scope: CoroutineScope,
    private val logger: StructuredLogger,
    private val intervalMinutes: Long = 5
) {
    private var job: Job? = null

    fun start(): Job {
        job?.cancel()
        val intervalMs = intervalMinutes * 60_000L
        job = scope.launch {
            try {
                deployer.cleanupOrphans()
            } catch (e: Exception) {
                logger.warn("orphan_cleanup_error", mapOf("error" to (e.message ?: "unknown") as Any?))
            }
            while (isActive) {
                delay(intervalMs)
                try {
                    deployer.cleanupOrphans()
                } catch (e: Exception) {
                    logger.warn("orphan_cleanup_error", mapOf("error" to (e.message ?: "unknown") as Any?))
                }
            }
        }
        return job!!
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
