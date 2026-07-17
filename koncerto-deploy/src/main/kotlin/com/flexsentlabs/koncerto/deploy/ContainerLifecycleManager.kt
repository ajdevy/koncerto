package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.core.docker.KoncertoDockerLabels
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ContainerInstance(
    val containerId: String,
    val hostPort: Int,
    val baseUrl: String,
    // The following three are additive/default-valued so every existing call site (20+ in
    // TargetProjectDeployerTest alone) keeps compiling unchanged. Populated by tryRunContainer,
    // which already knows all three; they let the demo recorder reach the target by container
    // name on the shared Docker network instead of localhost:hostPort (which only resolves on
    // the host, not from inside another container).
    val containerName: String = "",
    val containerPort: Int = 0,
    val network: String = ""
) {
    val internalUrl: String get() = "http://$containerName:$containerPort"
}

class ContainerLifecycleManager(
    private val logger: StructuredLogger,
    private val portRange: IntRange = 32768..33000,
    private val networkName: String = "koncerto-network"
) {
    private val usedPorts = mutableSetOf<Int>()

    /**
     * Resolves the docker executable through the same test seam as [TargetProjectDeployer]
     * (`testDockerOverride` / `KONCERTO_TEST_DOCKER`). Without it this class would always shell
     * out to a real `docker` on PATH, so tests that inject a fake docker script silently had no
     * effect here. Defaults to "docker", so production behavior is unchanged.
     */
    private fun dockerExe(): String = TargetProjectDeployer.dockerCmd().first()

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
            val pb = ProcessBuilder("sh", "-c", "${dockerExe()} ps -a --format '{{.Ports}}' | grep -oE ':[0-9]+->' | grep -oE '[0-9]+'")
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
                dockerExe(), "build", "-f", dockerfilePath.toString(),
                "-t", tag, projectPath.toString()
            ).redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(600, TimeUnit.SECONDS) && p.exitValue() == 0
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

    fun runContainer(image: String, hostPort: Int, containerPort: Int, network: String? = null, envVars: Map<String, String> = emptyMap()): Result<ContainerInstance> {
        var currentPort = hostPort
        var lastError: String? = null

        for (attempt in 1..3) {
            val result = tryRunContainer(image, currentPort, containerPort, network, envVars)
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

    /** Builds the `docker run` argv, injecting one `-e KEY=VALUE` per env entry (order preserved). */
    internal fun buildRunCommand(
        containerName: String, network: String, hostPort: Int, containerPort: Int,
        image: String, envVars: Map<String, String>
    ): List<String> {
        val envArgs = envVars.flatMap { (k, v) -> listOf("-e", "$k=$v") }
        return listOf(
            dockerExe(), "run", "-d",
            "--name", containerName,
            "--label", "${KoncertoDockerLabels.MANAGED_BY}=${KoncertoDockerLabels.MANAGED_VALUE}",
            "--network", network,
            "-p", "$hostPort:$containerPort"
        ) + envArgs + image
    }

    private fun tryRunContainer(image: String, hostPort: Int, containerPort: Int, network: String?, envVars: Map<String, String> = emptyMap()): Result<ContainerInstance> {
        return try {
            val containerName = "koncerto-demo-${System.currentTimeMillis()}"
            val netArg = network ?: networkName
            val cmd = buildRunCommand(containerName, netArg, hostPort, containerPort, image, envVars)
            if (envVars.isNotEmpty()) {
                logger.info("docker_container_env", mapOf(
                    // Never log values in full — mask so secrets don't leak into logs.
                    "env" to (envVars.entries.joinToString(",") { "${it.key}=${SecretsFile.mask(it.value)}" } as Any?)
                ))
            }
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
            // host.docker.internal only resolves from WITHIN a container (via Docker's bridge-mode
            // embedded DNS) — the demo recorder (Playwright/xcrun/adb) always runs natively on this
            // same host, where that hostname doesn't resolve at all. The container's port is
            // published to the host, so localhost reaches it correctly either way.
            Result.success(ContainerInstance(cid, hostPort, "http://localhost:$hostPort", containerName, containerPort, netArg))
        } catch (e: Exception) {
            Result.failure(RuntimeException("docker run error: ${e.message}"))
        }
    }

    fun waitForHealthy(containerId: String, timeoutSec: Int = 60): Result<Unit> {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                val pb = ProcessBuilder(
                    dockerExe(), "inspect", containerId,
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
            val pb = ProcessBuilder(dockerExe(), "logs", containerId).redirectErrorStream(true)
            val p = pb.start()
            val logs = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            logs
        } catch (_: Exception) { "could not capture logs" }
    }

    fun stopAndRemove(containerId: String) {
        try {
            ProcessBuilder(dockerExe(), "rm", "-f", containerId).start().waitFor(10, TimeUnit.SECONDS)
            logger.info("docker_container_removed", mapOf("id" to (containerId as Any?)))
        } catch (_: Exception) {}
    }

    /** Builds the `docker exec` argv that runs `command` inside `containerId` via a shell. */
    internal fun buildExecCommand(containerId: String, command: String): List<String> {
        return listOf(dockerExe(), "exec", containerId, "sh", "-c", command)
    }

    /**
     * Runs `command` inside the running container via `docker exec`, capturing combined
     * stdout/stderr. Bounded by `timeoutSec` so a stuck command (e.g. a hung migration) can't
     * stall the deploy indefinitely — mirrors the `docker compose up` timeout pattern in
     * TargetProjectDeployer: wait with a timeout first, then read whatever output resulted.
     */
    fun execCommand(containerId: String, command: String, timeoutSec: Int = 120): Result<String> {
        return try {
            val pb = ProcessBuilder(buildExecCommand(containerId, command)).redirectErrorStream(true)
            val p = pb.start()
            val completed = p.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)
            val output = p.inputStream.bufferedReader().use { it.readText() }
            if (!completed) {
                p.destroyForcibly()
                return Result.failure(RuntimeException("post-deploy command timed out after ${timeoutSec}s"))
            }
            if (p.exitValue() != 0) {
                return Result.failure(RuntimeException("post-deploy command failed (exit ${p.exitValue()}):\n$output"))
            }
            Result.success(output)
        } catch (e: Exception) {
            Result.failure(RuntimeException("post-deploy command error: ${e.message}"))
        }
    }
}
