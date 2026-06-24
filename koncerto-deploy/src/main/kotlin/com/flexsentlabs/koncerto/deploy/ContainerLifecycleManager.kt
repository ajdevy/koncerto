package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ContainerInstance(
    val containerId: String,
    val hostPort: Int,
    val baseUrl: String
)

class ContainerLifecycleManager(
    private val logger: StructuredLogger,
    private val portRange: IntRange = 32768..33000,
    private val networkName: String = "koncerto-network"
) {
    private val usedPorts = mutableSetOf<Int>()

    fun allocatePort(): Int {
        val occupied = getOccupiedPorts()
        val free = portRange.firstOrNull { it !in usedPorts && it !in occupied && isPortFree(it) }
            ?: throw IllegalStateException("No free ports in range $portRange")
        usedPorts.add(free)
        logger.debug("port_allocated", mapOf("port" to (free.toString() as Any?), "occupied" to (occupied.toString() as Any?)))
        return free
    }

    private fun getOccupiedPorts(): Set<Int> {
        return try {
            val pb = ProcessBuilder("sh", "-c", "docker ps -a --format '{{.Ports}}' | grep -oE ':[0-9]+->' | grep -oE '[0-9]+'")
                .redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            out.lines().filter { it.isNotBlank() }.map { it.toInt() }.toSet()
        } catch (e: Exception) {
            logger.warn("port_query_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
            emptySet()
        }
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket(port).use { it.setReuseAddress(true); true }
        } catch (_: Exception) {
            false
        }
    }

    fun releasePort(port: Int) {
        usedPorts.remove(port)
    }

    fun buildImage(projectPath: Path, dockerfilePath: Path, tag: String): Result<Unit> {
        return try {
            val pb = ProcessBuilder(
                "docker", "build", "-f", dockerfilePath.toString(),
                "-t", tag, projectPath.toString()
            ).redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0
            if (ok) {
                logger.info("docker_build_ok", mapOf("tag" to (tag as Any?)))
                Result.success(Unit)
            } else {
                logger.warn("docker_build_failed", mapOf("tag" to (tag as Any?), "output" to (output.take(500) as Any?)))
                Result.failure(RuntimeException("docker build failed:\n$output"))
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("docker build error: ${e.message}"))
        }
    }

    fun runContainer(image: String, hostPort: Int, containerPort: Int, network: String? = null): Result<ContainerInstance> {
        var currentPort = hostPort
        var lastError: String? = null

        for (attempt in 1..3) {
            val result = tryRunContainer(image, currentPort, containerPort, network)
            if (result.isSuccess) {
                return result
            }

            val error = result.exceptionOrNull()?.message ?: "unknown error"
            lastError = error
            logger.warn("container_start_attempt_failed", mapOf(
                "attempt" to (attempt.toString() as Any?),
                "port" to (currentPort.toString() as Any?),
                "error" to (error.take(200) as Any?)
            ))

            if (error.contains("port is already allocated", ignoreCase = true) || error.contains("Bind for", ignoreCase = true)) {
                releasePort(currentPort)
                currentPort = allocatePort()
            } else {
                break
            }
        }

        return Result.failure(RuntimeException("docker run failed after retries: $lastError"))
    }

    private fun tryRunContainer(image: String, hostPort: Int, containerPort: Int, network: String?): Result<ContainerInstance> {
        return try {
            val containerName = "koncerto-demo-${System.currentTimeMillis()}"
            val netArg = network ?: networkName
            val cmd = listOf(
                "docker", "run", "-d",
                "--name", containerName,
                "--network", netArg,
                "-p", "$hostPort:$containerPort",
                image
            )
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            val ok = p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) {
                return Result.failure(RuntimeException("docker run failed:\n$output"))
            }
            val cid = output.takeLast(64)
            logger.info("docker_container_started", mapOf(
                "name" to (containerName as Any?), "port" to (hostPort.toString() as Any?)
            ))
            Result.success(ContainerInstance(cid, hostPort, "http://host.docker.internal:$hostPort"))
        } catch (e: Exception) {
            Result.failure(RuntimeException("docker run error: ${e.message}"))
        }
    }

    fun waitForHealthy(containerId: String, timeoutSec: Int = 60): Result<Unit> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val pb = ProcessBuilder(
                    "docker", "inspect", containerId,
                    "--format", "{{.State.Status}}"
                ).redirectErrorStream(true)
                val p = pb.start()
                val status = p.inputStream.bufferedReader().readText().trim()
                p.waitFor(5, TimeUnit.SECONDS)
                if (status == "running") {
                    Thread.sleep(3000)
                    return Result.success(Unit)
                }
            } catch (_: Exception) {}
            Thread.sleep(2000)
        }
        return Result.failure(RuntimeException("container not healthy within ${timeoutSec}s"))
    }

    fun captureLogs(containerId: String): String {
        return try {
            val pb = ProcessBuilder("docker", "logs", containerId).redirectErrorStream(true)
            val p = pb.start()
            val logs = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            logs
        } catch (_: Exception) { "could not capture logs" }
    }

    fun stopAndRemove(containerId: String) {
        try {
            ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
            logger.info("docker_container_removed", mapOf("id" to (containerId as Any?)))
        } catch (_: Exception) {}
    }
}
