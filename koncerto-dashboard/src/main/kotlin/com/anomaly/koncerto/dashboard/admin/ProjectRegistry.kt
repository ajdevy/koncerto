package com.anomaly.koncerto.dashboard.admin

import com.anomaly.koncerto.core.config.ProjectConfig
import java.util.concurrent.ConcurrentHashMap

class ProjectRegistry {
    private val projects = ConcurrentHashMap<String, ProjectConfig>()

    fun registerProject(slug: String, config: ProjectConfig) {
        projects[slug] = config
    }

    fun getProject(slug: String): ProjectConfig? = projects[slug]

    fun getAllProjects(): Map<String, ProjectConfig> = projects.toMap()

    fun getProjectCount(): Int = projects.size
}
