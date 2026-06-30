package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.util.concurrent.TimeUnit

data class DockerCleanupReport(
    val containersRemoved: Int = 0,
    val imagesRemoved: Int = 0,
    val composeProjectsRemoved: Int = 0,
    val danglingImagesPruned: Int = 0,
    val protectedSkipped: Int = 0
)

class DockerLaunchCleaner(
    private val logger: StructuredLogger,
    private val dockerCmd: (Array<out String>) -> Array<String> = { TargetProjectDeployer.dockerCmd(*it) }
) {
    suspend fun cleanOnLaunch(): DockerCleanupReport {
        logger.info("docker_launch_cleanup_start", emptyMap())
        var report = DockerCleanupReport()

        val (containersRemoved, protectedSkipped) = cleanOrphanContainers()
        report = report.copy(
            containersRemoved = report.containersRemoved + containersRemoved,
            protectedSkipped = report.protectedSkipped + protectedSkipped
        )
        report = report.copy(
            imagesRemoved = report.imagesRemoved + cleanOrphanImages()
        )
        report = report.copy(
            composeProjectsRemoved = report.composeProjectsRemoved + cleanOrphanComposeProjects()
        )
        report = report.copy(
            danglingImagesPruned = pruneDanglingImages()
        )

        logger.info(
            "docker_launch_cleanup_complete",
            mapOf(
                "containers_removed" to report.containersRemoved.toString(),
                "images_removed" to report.imagesRemoved.toString(),
                "compose_projects_removed" to report.composeProjectsRemoved.toString(),
                "dangling_pruned" to report.danglingImagesPruned.toString(),
                "protected_skipped" to report.protectedSkipped.toString()
            )
        )
        return report
    }

    private fun cleanOrphanContainers(): Pair<Int, Int> {
        var removed = 0
        var protected = 0

        val containers = listContainers()
        for (container in containers) {
            if (DockerEntityPolicy.isProtectedContainer(
                    container.name,
                    container.status,
                    container.composeProject,
                    container.image,
                    container.labels
                )
            ) {
                protected++
                logger.debug(
                    "docker_launch_cleanup_protected_container",
                    mapOf("name" to (container.name ?: container.id), "status" to container.status)
                )
                continue
            }

            if (!DockerEntityPolicy.isOrphanContainer(
                    container.name,
                    container.status,
                    container.composeProject,
                    container.image,
                    container.labels
                )
            ) {
                continue
            }

            if (runDocker("rm", "-f", container.id)) {
                removed++
                logger.info(
                    "docker_launch_cleanup_container_removed",
                    mapOf("id" to container.id, "name" to (container.name ?: "unknown"))
                )
            }
        }

        return removed to protected
    }

    private fun cleanOrphanImages(): Int {
        var removed = 0
        val images = listImages()
        for (image in images) {
            if (!DockerEntityPolicy.isOrphanImage(image.repositoryTag)) continue
            if (DockerEntityPolicy.isProtectedImage(image.repositoryTag)) continue
            if (runDocker("rmi", "-f", image.repositoryTag)) {
                removed++
                logger.info(
                    "docker_launch_cleanup_image_removed",
                    mapOf("image" to image.repositoryTag)
                )
            }
        }
        return removed
    }

    private fun cleanOrphanComposeProjects(): Int {
        var removed = 0
        val projects = listComposeProjects()
        for (project in projects) {
            if (!DockerEntityPolicy.isOrphanComposeProject(project)) continue
            if (runDocker("compose", "-p", project, "down", "--remove-orphans", "--volumes")) {
                removed++
                logger.info(
                    "docker_launch_cleanup_compose_removed",
                    mapOf("project" to project)
                )
            }
        }
        return removed
    }

    private fun pruneDanglingImages(): Int {
        val before = listDanglingImageIds().size
        if (before == 0) return 0
        runDocker("image", "prune", "-f", "--filter", "label=$DockerEntityPolicy.MANAGED_LABEL=${DockerEntityPolicy.MANAGED_VALUE}")
        val danglingAfterManaged = listDanglingImageIds().size
        if (danglingAfterManaged > 0) {
            runDocker("image", "prune", "-f")
        }
        val after = listDanglingImageIds().size
        return (before - after).coerceAtLeast(0)
    }

    private data class ContainerInfo(
        val id: String,
        val name: String?,
        val status: String,
        val image: String,
        val composeProject: String?,
        val labels: Map<String, String>
    )

    private data class ImageInfo(val repositoryTag: String)

    private fun listContainers(): List<ContainerInfo> {
        val output = dockerOutput(
            "ps", "-a",
            "--format", "{{.ID}}|{{.Names}}|{{.Status}}|{{.Image}}|{{.Label \"com.docker.compose.project\"}}|{{.Label \"${DockerEntityPolicy.MANAGED_LABEL}\"}}"
        ) ?: return emptyList()

        return output.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("|", limit = 6)
                if (parts.size < 4) return@mapNotNull null
                val labels = mutableMapOf<String, String>()
                if (parts.size > 5 && parts[5].isNotBlank()) {
                    labels[DockerEntityPolicy.MANAGED_LABEL] = parts[5]
                }
                ContainerInfo(
                    id = parts[0].trim(),
                    name = parts[1].trim().ifBlank { null },
                    status = parts[2].trim(),
                    image = parts[3].trim(),
                    composeProject = parts.getOrNull(4)?.trim()?.ifBlank { null },
                    labels = labels
                )
            }
    }

    private fun listImages(): List<ImageInfo> {
        val tagged = dockerOutput("images", "--format", "{{.Repository}}:{{.Tag}}") ?: return emptyList()
        return tagged.lines()
            .filter { it.isNotBlank() && !it.contains("<none>:<none>") }
            .map { ImageInfo(it.trim()) }
    }

    private fun listDanglingImageIds(): List<String> {
        return dockerOutput("images", "-f", "dangling=true", "--format", "{{.ID}}")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun listComposeProjects(): List<String> {
        return dockerOutput("compose", "ls", "--format", "{{.Name}}")
            ?.lines()
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun dockerOutput(vararg args: String): String? {
        return try {
            val pb = ProcessBuilder(*dockerCmd(args)).redirectErrorStream(true)
            val p = pb.start()
            val completed = p.waitFor(15, TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                return null
            }
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) {
            logger.warn("docker_launch_cleanup_scan_failed", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    private fun runDocker(vararg args: String): Boolean {
        return try {
            val pb = ProcessBuilder(*dockerCmd(args)).redirectErrorStream(true)
            val p = pb.start()
            val completed = p.waitFor(30, TimeUnit.SECONDS)
            completed && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }
}
