package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class DeployResult(
    val url: String?,
    val success: Boolean,
    val error: String?,
    val logs: String?,
    val isCompose: Boolean = false,
    val tag: String? = null
) {
    companion object {
        fun success(url: String, isCompose: Boolean = false, tag: String? = null) =
            DeployResult(url, true, null, null, isCompose, tag)
        fun failure(error: String, logs: String? = null) =
            DeployResult(null, false, error, logs, false, null)
    }
}

data class DeployConfig(
    val repoFullName: String,
    val prBranch: String,
    val baseBranch: String = "main",
    val projectPath: Path
)

class TargetProjectDeployer(
    private val configDetector: DockerConfigDetector,
    private val frameworkDetector: FrameworkDetector,
    private val dockerfileGenerator: DockerfileGenerator,
    private val containerManager: ContainerLifecycleManager,
    private val logger: StructuredLogger
) {
    suspend fun deploy(config: DeployConfig): DeployResult {
        val projectPath = config.projectPath
        val tag = "koncerto-demo-${config.prBranch.replace("/", "-").lowercase()}"

        // Phase 1: Detect existing Docker config
        val existingConfig = configDetector.detect(projectPath)
        if (existingConfig != null) {
            logger.info("deploy_docker_config_found", mapOf("type" to existingConfig.type.name))
            return buildAndRun(existingConfig, projectPath, tag)
        }

        // Phase 2: Check for framework and generate Dockerfile
        val framework = frameworkDetector.detectFramework(projectPath)
        if (framework == null) {
            return DeployResult.failure("Could not detect project framework type")
        }

        // Generate Dockerfile and write it to project root temporarily
        val dockerfileContent = dockerfileGenerator.generate(framework)
        val tempDockerfile = projectPath.resolve("Dockerfile.koncerto")
        try {
            tempDockerfile.toFile().writeText(dockerfileContent)
            val detected = DetectedDockerConfig(DockerConfigType.DOCKERFILE, dockerfile = tempDockerfile)
            return buildAndRun(detected, projectPath, tag)
        } finally {
            tempDockerfile.toFile().delete()
        }
    }

    private val webPorts = setOf(80, 3000, 5000, 8000, 8080, 8443, 3001, 5173, 4200, 8001, 9090)

    private fun buildAndRun(config: DetectedDockerConfig, projectPath: Path, tag: String): DeployResult {
        return try {
            when (config.type) {
                DockerConfigType.DOCKER_COMPOSE -> {
                    val composeFile = requireNotNull(config.composeFile) { "DOCKER_COMPOSE config missing composeFile" }
                    deployWithCompose(composeFile, projectPath, tag)
                }
                DockerConfigType.DOCKERFILE -> {
                    val dockerfile = requireNotNull(config.dockerfile) { "DOCKERFILE config missing dockerfile" }
                    deployWithDockerfile(dockerfile, projectPath, tag)
                }
            }
        } catch (e: Exception) {
            DeployResult.failure("Deployment error: ${e.message}")
        }
    }

    private fun deployWithDockerfile(dockerfile: Path, projectPath: Path, tag: String, network: String? = null): DeployResult {
        val buildResult = containerManager.buildImage(projectPath, dockerfile, tag)
        if (buildResult.isFailure) {
            return DeployResult.failure(
                "Docker build failed",
                buildResult.exceptionOrNull()?.message
            )
        }

        val framework = frameworkDetector.detectFramework(projectPath)
        val containerPort = framework?.ports?.firstOrNull() ?: 8080
        val hostPort = containerManager.allocatePort()

        val runResult = containerManager.runContainer(tag, hostPort, containerPort, network)
        if (runResult.isFailure) {
            containerManager.releasePort(hostPort)
            return DeployResult.failure(
                "Container start failed",
                runResult.exceptionOrNull()?.message
            )
        }

        val container = runResult.getOrThrow()
        val healthResult = containerManager.waitForHealthy(container.containerId)

        if (healthResult.isFailure) {
            val logs = containerManager.captureLogs(container.containerId)
            containerManager.stopAndRemove(container.containerId)
            containerManager.releasePort(hostPort)
            return DeployResult.failure("Health check failed", logs)
        }

        logger.info("deploy_success", mapOf("url" to container.baseUrl))
        return DeployResult.success(container.baseUrl, tag = tag)
    }

    private fun deployWithCompose(composeFile: Path, projectPath: Path, tag: String): DeployResult {
        val projectName = "koncerto-demo"
        try {
            // Clean up any previous compose instance
            ProcessBuilder(
                "docker", "compose", "-p", projectName,
                "-f", composeFile.toString(), "down", "--remove-orphans"
            ).directory(projectPath.toFile())
             .redirectErrorStream(true)
             .start()
             .waitFor(30, TimeUnit.SECONDS)

            val pb = ProcessBuilder(
                "docker", "compose", "-p", projectName,
                "-f", composeFile.toString(), "up", "-d"
            ).directory(projectPath.toFile()).redirectErrorStream(true)
            val p = pb.start()
            val completed = p.waitFor(120, TimeUnit.SECONDS)
            val output = p.inputStream.bufferedReader().use { it.readText() }
            if (!completed) {
                p.destroyForcibly()
                return DeployResult.failure("docker compose up timed out")
            }
            if (p.exitValue() != 0) {
                return DeployResult.failure("docker compose up failed", output)
            }

            val psPb = ProcessBuilder(
                "docker", "compose", "-p", projectName,
                "-f", composeFile.toString(), "ps"
            ).directory(projectPath.toFile()).redirectErrorStream(true)
            val psP = psPb.start()
            val psCompleted = psP.waitFor(5, TimeUnit.SECONDS)
            val psOutput = psP.inputStream.bufferedReader().use { it.readText() }
            if (!psCompleted) {
                psP.destroyForcibly()
            }

            val portMatch = Regex("""(\d+)->\d+/tcp""").find(psOutput)
            val hostPort = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0

            if (hostPort > 0 && hostPort in webPorts) {
                logger.info("deploy_compose_success", mapOf("port" to hostPort.toString()))
                return DeployResult.success("http://host.docker.internal:$hostPort", isCompose = true, tag = tag)
            }

            // Infra-only compose: keep infra running, detect and deploy the app
            logger.info("deploy_compose_infra_only", mapOf(
                "port" to hostPort.toString(),
                "action" to "falling_through_to_app_build"
            ))
            val composeNetwork = resolveComposeNetwork(projectPath, composeFile)
            return deployAppOnNetwork(projectPath, tag, composeNetwork)
        } catch (e: Exception) {
            return DeployResult.failure("docker compose error: ${e.message}")
        }
    }

    private fun resolveComposeNetwork(projectPath: Path, composeFile: Path, projectName: String = "koncerto-demo"): String {
        return try {
            val pb = ProcessBuilder(
                "docker", "compose", "-p", projectName,
                "-f", composeFile.toString(),
                "ps", "--format", "{{.Name}}"
            ).directory(projectPath.toFile()).redirectErrorStream(true)
            val p = pb.start()
            p.waitFor(5, TimeUnit.SECONDS)
            // Filter out docker-compose structured log lines (e.g. time="..." level=warning)
            val names = p.inputStream.bufferedReader().use { it.readText() }.lines()
                .filter { it.isNotBlank() && !it.startsWith("time=") }
            if (names.isNotEmpty()) {
                val inspectPb = ProcessBuilder(
                    "docker", "inspect", names.last(),
                    "--format", "{{.HostConfig.NetworkMode}}"
                ).redirectErrorStream(true)
                val ip = inspectPb.start()
                ip.waitFor(5, TimeUnit.SECONDS)
                val network = ip.inputStream.bufferedReader().use { it.readText() }.trim()
                if (network.isNotBlank()) return network
            }
            "${projectName}_default"
        } catch (_: Exception) {
            "${projectName}_default"
        }
    }

    private fun deployAppOnNetwork(projectPath: Path, tag: String, network: String): DeployResult {
        val framework = frameworkDetector.detectFramework(projectPath)
            ?: return DeployResult.failure("Could not detect framework for app build")
        val dockerfileContent = dockerfileGenerator.generate(framework)
        val tempDockerfile = projectPath.resolve("Dockerfile.koncerto")
        try {
            tempDockerfile.toFile().writeText(dockerfileContent)
            return deployWithDockerfile(tempDockerfile, projectPath, tag, network)
        } finally {
            tempDockerfile.toFile().delete()
        }
    }

    suspend fun cleanup(config: DeployConfig) {
        val tag = "koncerto-demo-${config.prBranch.replace("/", "-").lowercase()}"
        logger.info("deploy_cleanup_start", mapOf("tag" to (tag as Any?)))

        // Stop and remove all containers with this image tag
        try {
            val findPb = ProcessBuilder(
                "docker", "ps", "-a", "--filter", "ancestor=$tag", "--format", "{{.ID}}"
            ).redirectErrorStream(true)
            val findP = findPb.start()
            findP.waitFor(10, TimeUnit.SECONDS)
            val ids = findP.inputStream.bufferedReader().use { it.readText() }.lines().filter { it.isNotBlank() }
            ids.forEach { id ->
                try {
                    ProcessBuilder("docker", "rm", "-f", id).start().waitFor(10, TimeUnit.SECONDS)
                } catch (_: Exception) {}
            }
            logger.info("deploy_cleanup_containers", mapOf("count" to (ids.size.toString() as Any?)))
        } catch (e: Exception) {
            logger.warn("deploy_cleanup_containers_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
        }

        // Remove the image
        try {
            ProcessBuilder("docker", "rmi", "-f", tag).start().waitFor(10, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        // Clean up compose project if compose file exists
        val composeFile = config.projectPath.resolve("docker-compose.yml")
        if (Files.exists(composeFile)) {
            try {
                val pb = ProcessBuilder(
                    "docker", "compose", "-p", "koncerto-demo",
                    "-f", composeFile.toString(), "down", "--remove-orphans", "--volumes"
                ).directory(config.projectPath.toFile()).redirectErrorStream(true).start()
                pb.waitFor(30, TimeUnit.SECONDS)
                logger.info("deploy_cleanup_compose", mapOf("status" to ("ok" as Any?)))
            } catch (e: Exception) {
                logger.warn("deploy_cleanup_compose_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
            }
        }
    }

    suspend fun cleanupOrphans() {
        logger.info("deploy_orphan_cleanup_start", emptyMap())

        // Find and remove all containers with name koncerto-demo-*
        try {
            val findPb = ProcessBuilder(
                "docker", "ps", "-a", "--filter", "name=koncerto-demo", "--format", "{{.ID}}"
            ).redirectErrorStream(true)
            val findP = findPb.start()
            findP.waitFor(10, TimeUnit.SECONDS)
            val ids = findP.inputStream.bufferedReader().use { it.readText() }.lines().filter { it.isNotBlank() }
            if (ids.isNotEmpty()) {
                ids.forEach { id ->
                    try {
                        ProcessBuilder("docker", "rm", "-f", id).start().waitFor(10, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }
                logger.info("deploy_orphan_containers_removed", mapOf("count" to (ids.size.toString() as Any?)))
            }
        } catch (e: Exception) {
            logger.warn("deploy_orphan_container_scan_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
        }

        // Find and remove all images with name koncerto-demo-*
        try {
            val findImgPb = ProcessBuilder(
                "docker", "images", "--format", "{{.Repository}}:{{.Tag}}",
                "--filter", "reference=koncerto-demo-*"
            ).redirectErrorStream(true)
            val findImgP = findImgPb.start()
            findImgP.waitFor(10, TimeUnit.SECONDS)
            val tags = findImgP.inputStream.bufferedReader().use { it.readText() }.lines().filter { it.isNotBlank() }
            if (tags.isNotEmpty()) {
                tags.forEach { tag ->
                    try {
                        ProcessBuilder("docker", "rmi", "-f", tag).start().waitFor(10, TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                }
                logger.info("deploy_orphan_images_removed", mapOf("count" to (tags.size.toString() as Any?)))
            }
        } catch (e: Exception) {
            logger.warn("deploy_orphan_image_scan_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
        }

        // Remove any dangling compose project
        try {
            val pb = ProcessBuilder(
                "docker", "compose", "ls", "--format", "{{.Name}}"
            ).redirectErrorStream(true)
            val p = pb.start()
            p.waitFor(5, TimeUnit.SECONDS)
            val names = p.inputStream.bufferedReader().use { it.readText() }.lines()
                .filter { it.startsWith("koncerto-demo") }
            names.forEach { name ->
                try {
                    val downPb = ProcessBuilder("docker", "compose", "-p", name, "down", "--remove-orphans", "--volumes")
                        .redirectErrorStream(true).start()
                    downPb.waitFor(30, TimeUnit.SECONDS)
                    logger.info("deploy_orphan_compose_removed", mapOf("project" to (name as Any?)))
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            logger.warn("deploy_orphan_compose_scan_failed", mapOf("error" to (e.message ?: "unknown") as Any?))
        }
    }
}
