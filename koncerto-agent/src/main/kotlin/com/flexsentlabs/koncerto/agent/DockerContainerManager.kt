package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class DockerContainerManager(
    private val config: DockerConfig,
    private val workspacePath: Path,
    private val maxConcurrentAgents: Int,
    private val logger: StructuredLogger
) {
    fun createContainer(): String? {
        return try {
            val containerId = generateContainerId()
            val cpus = resolveCpus()
            val memory = resolveMemory()

            val issueDir = workspacePath.fileName.toString()
            val runCmd = buildString {
                append("docker run -d")
                append(" --name $containerId")
                append(" --cpus $cpus")
                append(" --memory $memory")
                append(" -v koncerto-workspace:/workspace")
                append(" -w /workspace/$issueDir")
                if (config.network) {
                    append(" --network koncerto-network")
                }
                append(" ${config.image}")
                append(" sleep infinity")
            }

            val pb = ProcessBuilder("bash", "-lc", runCmd)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            val completed = p.waitFor(30, TimeUnit.SECONDS)
            val exitCode = if (completed) p.exitValue() else -1

            if (exitCode != 0 || output.isBlank()) {
                logger.warn("container_create_failed", mapOf("exit_code" to exitCode.toString()))
                return null
            }

            val id = output.takeLast(64)
            logger.info("container_created", mapOf("container_id" to id, "cpus" to cpus.toString(), "memory" to memory))
            id
        } catch (e: Exception) {
            logger.warn("container_create_error", emptyMap(), "error" to (e.message ?: "unknown"))
            null
        }
    }

    fun isContainerRunning(containerId: String): Boolean {
        return try {
            val pb = ProcessBuilder("bash", "-lc", "docker inspect $containerId --format='{{.State.Status}}' 2>/dev/null")
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            output == "running"
        } catch (_: Exception) {
            false
        }
    }

    fun captureLogs(containerId: String): String? {
        return try {
            val pb = ProcessBuilder("bash", "-lc", "docker logs $containerId 2>&1")
            val p = pb.start()
            val logs = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            if (p.exitValue() == 0 && logs.isNotBlank()) logs else null
        } catch (_: Exception) {
            null
        }
    }

    fun removeContainer(containerId: String) {
        try {
            val pb = ProcessBuilder("bash", "-lc", "docker rm -f $containerId 2>/dev/null")
            val p = pb.start()
            p.waitFor(10, TimeUnit.SECONDS)
            logger.info("container_removed", mapOf("container_id" to containerId))
        } catch (e: Exception) {
            logger.warn("container_remove_failed", mapOf("container_id" to containerId),
                "error" to (e.message ?: "unknown"))
        }
    }

    private fun resolveCpus(): Double {
        val raw = config.cpu.lowercase()
        if (raw == "auto") {
            val cpus = Runtime.getRuntime().availableProcessors().toDouble() / maxConcurrentAgents
            return cpus.coerceAtLeast(0.5)
        }
        return raw.toDoubleOrNull() ?: 2.0
    }

    private fun resolveMemory(): String {
        val raw = config.memory.lowercase()
        if (raw == "auto") {
            val freeMem = osFreeMem()
            val memPerAgent = (freeMem * 0.8 / maxConcurrentAgents).toLong()
            val memMb = memPerAgent / (1024 * 1024)
            return "${memMb.coerceAtLeast(512)}m"
        }
        return raw
    }

    private fun osFreeMem(): Long {
        return try {
            val pb = ProcessBuilder("bash", "-lc", "free -b | awk '/Mem:/ {print \$7}'")
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS)
            output.toLongOrNull() ?: Runtime.getRuntime().maxMemory()
        } catch (_: Exception) {
            Runtime.getRuntime().maxMemory()
        }
    }

    fun pruneOldContainers(olderThanHours: Int = 24) {
        Companion.pruneOldContainers(logger, olderThanHours)
    }

    companion object {
        private var counter = 0L
        private val dockerDateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

        fun generateContainerId(): String {
            val id = "koncerto-agent-${System.currentTimeMillis()}-${counter++}"
            return id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        }

        fun pruneOldContainers(logger: StructuredLogger, olderThanHours: Int = 24) {
            try {
                val cutoff = System.currentTimeMillis() - olderThanHours * 60 * 60 * 1000L
                val pb = ProcessBuilder(
                    "bash", "-lc",
                    "docker ps -a --filter name=koncerto-agent- --format '{{.ID}}|{{.CreatedAt}}' 2>/dev/null"
                )
                val p = pb.start()
                val output = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(10, TimeUnit.SECONDS)

                if (output.isBlank()) {
                    logger.debug("container_prune_no_candidates", emptyMap())
                    return
                }

                var pruned = 0
                output.lines().forEach { line ->
                    val parts = line.split("|")
                    if (parts.size < 2) return@forEach
                    val id = parts[0].trim()
                    val createdAt = parts[1].trim()
                    val cleaned = createdAt.replace(" UTC", "").trim()

                    try {
                        val parsed = OffsetDateTime.parse(cleaned, dockerDateFormat)
                        val epoch = parsed.toInstant().toEpochMilli()
                        if (epoch < cutoff) {
                            logger.info("container_pruning_old", mapOf(
                                "container_id" to id,
                                "created_at" to createdAt,
                                "age_hours" to String.format("%.1f", (System.currentTimeMillis() - epoch) / 3600000.0)
                            ))
                            val rm = ProcessBuilder("bash", "-lc", "docker rm -f $id 2>/dev/null")
                            rm.start().waitFor(10, TimeUnit.SECONDS)
                            pruned++
                        }
                    } catch (e: Exception) {
                        logger.warn("container_prune_date_parse_failed", mapOf(
                            "container_id" to id,
                            "created_at" to createdAt
                        ))
                    }
                }

                if (pruned > 0) {
                    logger.info("container_prune_completed", mapOf("pruned_count" to pruned.toString()))
                } else {
                    logger.debug("container_prune_none_old_enough", emptyMap())
                }
            } catch (e: Exception) {
                logger.warn("container_prune_failed", emptyMap(), "error" to (e.message ?: "unknown"))
            }
        }
    }
}
