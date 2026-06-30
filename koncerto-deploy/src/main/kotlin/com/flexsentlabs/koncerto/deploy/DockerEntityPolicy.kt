package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.core.docker.KoncertoDockerLabels

/**
 * Rules for distinguishing Koncerto-managed orphans from critical resources that must survive launch cleanup.
 */
object DockerEntityPolicy {
    const val MANAGED_LABEL = KoncertoDockerLabels.MANAGED_BY
    const val MANAGED_VALUE = KoncertoDockerLabels.MANAGED_VALUE

    val PROTECTED_VOLUME_NAMES = setOf(
        "koncerto-workspace",
        "koncerto-data",
        "koncerto-logs",
        "koncerto-codex",
        "koncerto-claude"
    )

    val PROTECTED_COMPOSE_PROJECTS = setOf("koncerto")

    val ORPHAN_COMPOSE_PROJECT_PREFIX = "koncerto-demo"

    val ORPHAN_CONTAINER_PREFIXES = listOf(
        "koncerto-demo-"
    )

    val ORPHAN_IMAGE_PREFIXES = listOf(
        "koncerto-demo-",
        "koncerto-test-"
    )

    val PROTECTED_IMAGE_TAGS = setOf(
        "koncerto-agent:latest"
    )

    private fun isRunningStatus(status: String): Boolean =
        status.equals("running", ignoreCase = true) || status.startsWith("up ", ignoreCase = true)

    fun isProtectedVolume(name: String): Boolean {
        if (name in PROTECTED_VOLUME_NAMES) return true
        return PROTECTED_VOLUME_NAMES.any { protected ->
            name == "koncerto_$protected" || name.startsWith("koncerto_${protected}_")
        }
    }

    fun isProtectedComposeProject(project: String?): Boolean {
        if (project.isNullOrBlank()) return false
        return project in PROTECTED_COMPOSE_PROJECTS
    }

    fun isOrphanComposeProject(project: String): Boolean {
        return project.startsWith(ORPHAN_COMPOSE_PROJECT_PREFIX) &&
            !isProtectedComposeProject(project)
    }

    fun isProtectedImage(repositoryTag: String): Boolean {
        return repositoryTag in PROTECTED_IMAGE_TAGS
    }

    fun isOrphanImage(repositoryTag: String): Boolean {
        if (isProtectedImage(repositoryTag)) return false
        if (repositoryTag.startsWith("<none>")) return true
        return ORPHAN_IMAGE_PREFIXES.any { repositoryTag.startsWith(it) }
    }

    fun isProtectedContainer(
        name: String?,
        status: String,
        composeProject: String?,
        image: String,
        labels: Map<String, String> = emptyMap()
    ): Boolean {
        if (isRunningStatus(status)) {
            if (isProtectedComposeProject(composeProject)) return true
            if (name?.contains("koncerto-app") == true) return true
            if (name?.startsWith("koncerto-agent-") == true) return true
            if (name?.startsWith("koncerto-koncerto-app") == true) return true
            if (image.contains("koncerto-agent") && isRunningStatus(status)) return true
            return true
        }
        return false
    }

    fun isOrphanContainer(
        name: String?,
        status: String,
        composeProject: String?,
        image: String,
        labels: Map<String, String> = emptyMap()
    ): Boolean {
        if (isProtectedContainer(name, status, composeProject, image, labels)) return false

        if (labels[MANAGED_LABEL] == MANAGED_VALUE && !isRunningStatus(status)) {
            return true
        }

        if (name != null) {
            if (ORPHAN_CONTAINER_PREFIXES.any { name.startsWith(it) }) return true
            if (name.startsWith("koncerto-agent-")) return true
            if (isOrphanComposeProject(composeProject.orEmpty()) && !isRunningStatus(status)) {
                return true
            }
        }

        if (!isRunningStatus(status)) {
            if (ORPHAN_IMAGE_PREFIXES.any { image.startsWith(it) }) return true
            if (image.contains("koncerto-demo")) return true
            if (name != null && name.startsWith("koncerto-") && !name.startsWith("koncerto-koncerto-")) {
                return true
            }
        }

        return false
    }
}
