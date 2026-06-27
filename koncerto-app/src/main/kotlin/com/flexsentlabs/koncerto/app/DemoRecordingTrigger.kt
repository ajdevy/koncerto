package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.AdbRecorder
import com.flexsentlabs.koncerto.demo.recorder.AsciinemaRecorder
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.FfmpegRecorder
import com.flexsentlabs.koncerto.demo.recorder.PlaywrightRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.recorder.XcrunRecorder
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.report.LinearReportPublisher
import com.flexsentlabs.koncerto.demo.repository.SqliteDemoTaskRepository
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import com.flexsentlabs.koncerto.demo.storage.R2DemoStorage
import com.flexsentlabs.koncerto.deploy.ContainerLifecycleManager
import com.flexsentlabs.koncerto.deploy.DeployConfig
import com.flexsentlabs.koncerto.deploy.DockerConfigDetector
import com.flexsentlabs.koncerto.deploy.DockerfileGenerator
import com.flexsentlabs.koncerto.deploy.FrameworkDetector
import com.flexsentlabs.koncerto.deploy.TargetProjectDeployer
import com.flexsentlabs.koncerto.linear.DefaultLinearClient
import com.flexsentlabs.koncerto.linear.LinearGraphQLClient
import com.flexsentlabs.koncerto.logging.StderrSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import com.flexsentlabs.koncerto.workflow.WorkflowLoader
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.nio.file.Paths

object DemoRecordingTrigger {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 1) {
            println("Usage: DemoRecordingTrigger <issueIdentifier> [issueId] [projectSlug]")
            kotlin.system.exitProcess(1)
        }

        val issueIdentifier = args[0]
        val issueId = args.getOrNull(1)
        val projectSlug = args.getOrNull(2) ?: "promomesh"
        val workflowPath = System.getenv("KONCERTO_WORKFLOW_PATH") ?: "./WORKFLOW.md"
        val workspaceRoot = System.getenv("KONCERTO_WORKSPACE_ROOT") ?: "/workspace"
        val dbPath = System.getenv("KONCERTO_DB_PATH") ?: "/root/.koncerto/metrics.db"
        val logger = StructuredLogger(listOf(StderrSink()))

        val path = Paths.get(workflowPath)
        val def = WorkflowLoader.loadFromPath(path)
        val configMap = def.config
        val workflowFileDir = path.parent?.toString() ?: "."
        val config = ServiceConfig.fromMap(configMap, workflowFileDir)

        val workspacePath = Paths.get(workspaceRoot, issueIdentifier)
        if (!workspacePath.toFile().isDirectory) {
            println("Workspace not found for $issueIdentifier at $workspacePath")
            kotlin.system.exitProcess(1)
        }

        val resolvedIssueId = issueId ?: resolveIssueId(workspacePath, logger)
        if (resolvedIssueId == null) {
            println("Could not determine issue ID. Provide it as second argument.")
            kotlin.system.exitProcess(1)
        }

        println("Deploying target project for $issueIdentifier...")
        val deployResult = runBlocking { deployProject(config, workspacePath, issueIdentifier, logger) }
        if (deployResult == null) {
            println("Deployment failed, aborting")
            kotlin.system.exitProcess(1)
        }
        val targetUrl = deployResult.url ?: error("Deploy succeeded but no URL returned")
        println("Target project deployed at: $targetUrl")

        println("Recording demo for $issueIdentifier ($resolvedIssueId) at $targetUrl...")
        val recordingService = createRecordingService(config, dbPath, targetUrl, logger)
        val result = runBlocking {
            recordingService.requestRecording(
                issueId = resolvedIssueId,
                issueIdentifier = issueIdentifier,
                projectSlug = projectSlug,
                platform = null,
                trigger = DemoTrigger.MANUAL,
                targetUrl = targetUrl
            )
        }

        when (result) {
            is DemoResult.Success -> {
                val task = result.value
                println("SUCCESS: Demo task ${task.id}")
                println("  Status: ${task.status}")
                println("  Recording URL: ${task.recordingUrl ?: "still processing..."}")
                if (task.recordingUrl != null) {
                    println("\nDemo URL: ${task.recordingUrl}")
                }
            }
            is DemoResult.Failure -> {
                println("FAILURE: ${result.error.message}")
            }
        }

        println("Cleaning up deployed container...")
        val deployer = createDeployer(logger)
        val deployConfig = DeployConfig(
            repoFullName = "ajdevy/PromoMesh",
            prBranch = issueIdentifier,
            baseBranch = "main",
            projectPath = workspacePath
        )
        runBlocking { deployer.cleanup(deployConfig) }
        println("Cleanup complete")
    }

    private fun resolveIssueId(workspacePath: Path, logger: StructuredLogger): String? {
        val exhaustFile = workspacePath.resolve(".review-exhausted")
        if (exhaustFile.toFile().exists()) {
            val content = exhaustFile.toFile().readText()
            val match = Regex(""""issue_id":"([^"]+)"""").find(content)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private suspend fun deployProject(
        config: ServiceConfig, workspacePath: Path, prBranch: String, logger: StructuredLogger
    ): com.flexsentlabs.koncerto.deploy.DeployResult? {
        val deployer = createDeployer(logger)
        val repoFullName = resolveRepoFullName(workspacePath, config)
        if (repoFullName == null) {
            logger.warn("deploy_no_repo", emptyMap())
            return null
        }
        val deployConfig = DeployConfig(
            repoFullName = repoFullName,
            prBranch = prBranch,
            baseBranch = "main",
            projectPath = workspacePath
        )
        val result = deployer.deploy(deployConfig)
        if (result.success) {
            logger.info("deploy_ok", mapOf("url" to (result.url ?: "unknown")))
        } else {
            logger.warn("deploy_failed", mapOf("error" to (result.error ?: "unknown")))
        }
        return result
    }

    private fun createRecordingService(
        config: ServiceConfig, dbPath: String, targetUrl: String, logger: StructuredLogger
    ): DemoRecordingService {
        val dr = config.demoRecording
        val demoConfig = DemoConfig(
            enabled = dr.enabled,
            targetUrl = targetUrl,
            tempDir = "/tmp/koncerto-demo",
            maxRetries = dr.retry.maxAttempts,
            retryDelayMs = 5_000L,
            preflightTimeoutMs = 10_000L,
            retentionDays = 90,
            maxRecordingsPerSpace = 100,
            defaultPlatform = dr.platform.web,
            r2 = dr.storage?.let { s ->
                DemoConfig.R2Config(
                    endpoint = s.r2Endpoint, accessKey = s.r2AccessKey, secretKey = s.r2SecretKey,
                    bucketName = s.r2Bucket, publicUrlBase = s.publicUrlBase,
                    presignedUrlTtlSeconds = s.presignedUrlTtl, region = s.region
                )
            },
            ai = DemoConfig.AiConfig(
                model = dr.ai.model, timelineEnabled = dr.ai.timeline, reproStepsEnabled = dr.ai.reproSteps
            ),
            cleanupIntervalHours = dr.cleanupIntervalHours
        )

        val recorders: List<DemoRecorder> = listOf(
            PlaywrightRecorder(), AsciinemaRecorder(), AdbRecorder(), XcrunRecorder(), FfmpegRecorder()
        )
        val recorderFactory = RecorderFactory(recorders)
        val taskRepository = SqliteDemoTaskRepository(dbPath)
        val r2 = demoConfig.r2 ?: error("R2 storage not configured")
        val storage = R2DemoStorage(
            endpoint = r2.endpoint, accessKey = r2.accessKey, secretKey = r2.secretKey,
            bucketName = r2.bucketName, publicUrlBase = r2.publicUrlBase,
            presignedUrlTtlSeconds = r2.presignedUrlTtlSeconds, region = r2.region
        )
        val firstProject = config.projects.values.firstOrNull() ?: error("No project configured")
        val graphqlClient = LinearGraphQLClient(endpoint = firstProject.tracker.endpoint, apiKey = firstProject.tracker.apiKey)
        val slug = firstProject.tracker.projectSlug ?: error("Missing project slug")
        val linearClient = DefaultLinearClient(graphqlClient, slug)
        val reporter = LinearReportPublisher(linearClient)

        return DemoRecordingService(
            config = demoConfig, taskRepository = taskRepository, recorderFactory = recorderFactory,
            storage = storage, reporter = reporter, reportGenerator = DemoReportGenerator(),
            metrics = DemoMetricsRecorder(), auditLogger = DemoAuditLogger()
        )
    }

    private fun createDeployer(logger: StructuredLogger): TargetProjectDeployer {
        return TargetProjectDeployer(
            configDetector = DockerConfigDetector(), frameworkDetector = FrameworkDetector(),
            dockerfileGenerator = DockerfileGenerator(),
            containerManager = ContainerLifecycleManager(logger), logger = logger
        )
    }

    private fun resolveRepoFullName(workspacePath: Path, config: ServiceConfig): String? {
        val gitConfigPath = workspacePath.resolve(".git/config")
        if (!gitConfigPath.toFile().exists()) return null
        return try {
            val content = gitConfigPath.toFile().readText()
            val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
                .find(content, content.indexOf("[remote \"origin\"]"))
            match?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }
}
