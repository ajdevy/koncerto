package com.flexsentlabs.koncerto.agent

import com.flexsentlabs.koncerto.core.config.DockerConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path
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

            val runCmd = buildString {
                append("docker run -d")
                append(" --name $containerId")
                append(" --cpus $cpus")
                append(" --memory $memory")
                append(" -v ${workspacePath.toAbsolutePath()}:/workspace")
                append(" -w /workspace")
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

    companion object {
        private var counter = 0L

        fun generateContainerId(): String {
            val id = "koncerto-agent-${System.currentTimeMillis()}-${counter++}"
            return id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        }
    }
}
