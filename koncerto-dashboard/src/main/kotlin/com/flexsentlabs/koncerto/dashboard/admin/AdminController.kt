package com.flexsentlabs.koncerto.dashboard.admin

import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.metrics.MetricsRepository
import kotlinx.serialization.Serializable
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/v1/admin")
class AdminController @Autowired constructor(
    private val config: ServiceConfig,
    private val metricsRepository: MetricsRepository? = null,
    private val projectRegistry: ProjectRegistry? = null
) {
    @Serializable
    data class ProjectSummary(
        val slug: String,
        val projectSlug: String,
        val projectAdmin: String?,
        val agentKind: String,
        val activeCount: Int
    )

    @Serializable
    data class ProjectDetail(
        val slug: String,
        val tracker: TrackerInfo,
        val agent: AgentInfo,
        val workspace: WorkspaceInfo,
        val notifications: NotificationsInfo
    )

    @Serializable
    data class TrackerInfo(
        val kind: String,
        val projectSlug: String,
        val projectAdmin: String?
    )

    @Serializable
    data class AgentInfo(
        val kind: String,
        val maxConcurrentAgents: Int,
        val maxTurns: Int,
        val stages: List<String>
    )

    @Serializable
    data class WorkspaceInfo(
        val root: String
    )

    @Serializable
    data class NotificationsInfo(
        val onCompleted: Boolean,
        val onFailed: Boolean,
        val onStalled: Boolean
    )

    @Serializable
    data class TenantSummary(
        val tenantSlug: String,
        val projectCount: Int,
        val agentKinds: List<String>
    )

    @Serializable
    data class QuotaUsage(
        val projectSlug: String,
        val totalTokens: Long,
        val totalRuns: Int
    )

    private fun authorized(adminKey: String?): Boolean {
        val expected = config.adminApiKey ?: return false
        return adminKey == expected
    }

    @GetMapping("/projects", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listProjects(
        @RequestHeader("X-Admin-Key") adminKey: String?
    ): ResponseEntity<List<ProjectSummary>> {
        if (!authorized(adminKey)) return ResponseEntity.status(401).build()
        val allMetrics = metricsRepository?.findAll() ?: emptyList()
        val projectCounts = allMetrics.groupBy { it.projectSlug }.mapValues { it.value.size }
        val summaries = config.projects.map { (slug, pc) ->
            ProjectSummary(
                slug = slug,
                projectSlug = pc.tracker.projectSlug,
                projectAdmin = pc.tracker.projectAdmin,
                agentKind = pc.agent.kind,
                activeCount = projectCounts[pc.tracker.projectSlug] ?: 0
            )
        }
        return ResponseEntity.ok(summaries)
    }

    @GetMapping("/projects/{projectSlug}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getProjectDetail(
        @PathVariable projectSlug: String,
        @RequestHeader("X-Admin-Key") adminKey: String?
    ): Mono<ResponseEntity<ProjectDetail>> {
        if (!authorized(adminKey)) return Mono.just(ResponseEntity.status(401).build())
        val pc = projectRegistry?.getProject(projectSlug)
            ?: config.projects[projectSlug]
            ?: return Mono.just(ResponseEntity.notFound().build())
        return Mono.just(ResponseEntity.ok(
            ProjectDetail(
                slug = projectSlug,
                tracker = TrackerInfo(
                    kind = pc.tracker.kind,
                    projectSlug = pc.tracker.projectSlug,
                    projectAdmin = pc.tracker.projectAdmin
                ),
                agent = AgentInfo(
                    kind = pc.agent.kind,
                    maxConcurrentAgents = pc.agent.maxConcurrentAgents,
                    maxTurns = pc.agent.maxTurns,
                    stages = pc.agent.stages.keys.toList()
                ),
                workspace = WorkspaceInfo(root = pc.workspace.root),
                notifications = NotificationsInfo(
                    onCompleted = pc.notifications.onCompleted,
                    onFailed = pc.notifications.onFailed,
                    onStalled = pc.notifications.onStalled
                )
            )
        ))
    }

    @GetMapping("/tenants", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listTenants(
        @RequestHeader("X-Admin-Key") adminKey: String?
    ): ResponseEntity<List<TenantSummary>> {
        if (!authorized(adminKey)) return ResponseEntity.status(401).build()
        val tenants = config.projects.values
            .groupBy { it.tracker.projectSlug }
            .map { (slug, projects) ->
                TenantSummary(
                    tenantSlug = slug,
                    projectCount = projects.size,
                    agentKinds = projects.map { it.agent.kind }.distinct()
                )
            }
        return ResponseEntity.ok(tenants)
    }

    @GetMapping("/quotas", produces = [MediaType.APPLICATION_JSON_VALUE])
    suspend fun listQuotas(
        @RequestHeader("X-Admin-Key") adminKey: String?
    ): ResponseEntity<List<QuotaUsage>> {
        if (!authorized(adminKey)) return ResponseEntity.status(401).build()
        val allMetrics = metricsRepository?.findAll() ?: emptyList()
        val quotas = allMetrics.groupBy { it.projectSlug ?: "unknown" }.map { (slug, metrics) ->
            QuotaUsage(
                projectSlug = slug,
                totalTokens = metrics.sumOf { it.totalTokens },
                totalRuns = metrics.size
            )
        }
        return ResponseEntity.ok(quotas)
    }
}
