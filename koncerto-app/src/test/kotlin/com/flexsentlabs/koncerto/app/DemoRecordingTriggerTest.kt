package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.DemoRecordingConfig
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DemoRecordingTriggerTest {

    @Test
    fun `object exists and is accessible`() {
        assertThat(DemoRecordingTrigger).isNotNull()
    }

    @Test
    fun `object is singleton via identity`() {
        val a: DemoRecordingTrigger = DemoRecordingTrigger
        val b: DemoRecordingTrigger = DemoRecordingTrigger
        assertThat(a === b).isTrue()
    }

    @Test
    fun `config loads with demo_recording enabled`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf(
                    "enabled" to true,
                    "trigger" to "review_passed",
                    "target_url" to "http://localhost:8080"
                )
            ),
            "."
        )
        assertThat(config.demoRecording.enabled).isTrue()
    }

    @Test
    fun `config loads with default demo_recording config`() {
        val config = ServiceConfig.fromMap(
            mapOf("poll_interval_ms" to 15000),
            "."
        )
        assertThat(config.demoRecording.enabled).isEqualTo(DemoRecordingConfig().enabled)
    }

    @Test
    fun `config demo_recording has default trigger value`() {
        val config = ServiceConfig.fromMap(
            mapOf("poll_interval_ms" to 15000),
            "."
        )
        assertThat(config.demoRecording.trigger).isEqualTo("review_passed")
    }

    @Test
    fun `config demo_recording loads with full storage config`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf(
                    "enabled" to true,
                    "target_url" to "http://app:3000",
                    "storage" to mapOf(
                        "r2_endpoint" to "https://r2.example.com",
                        "r2_bucket" to "my-bucket",
                        "r2_access_key" to "ak123",
                        "r2_secret_key" to "sk456",
                        "public_url_base" to "https://pub.example.com",
                        "presigned_url_ttl" to 3600,
                        "region" to "weur"
                    ),
                    "ai" to mapOf(
                        "model" to "pro",
                        "timeline" to true,
                        "repro_steps" to true
                    ),
                    "retry" to mapOf(
                        "max_attempts" to 5,
                        "backoff" to "linear"
                    ),
                    "error" to mapOf(
                        "on_failure" to "log_only"
                    ),
                    "cleanup_interval_hours" to 12
                )
            ),
            "."
        )
        val dr = config.demoRecording
        assertThat(dr.targetUrl).isEqualTo("http://app:3000")
        assertThat(dr.storage).isNotNull()
        assertThat(dr.storage!!.r2Bucket).isEqualTo("my-bucket")
        assertThat(dr.storage!!.region).isEqualTo("weur")
        assertThat(dr.storage!!.presignedUrlTtl).isEqualTo(3600)
        assertThat(dr.ai.model).isEqualTo("pro")
        assertThat(dr.ai.timeline).isTrue()
        assertThat(dr.ai.reproSteps).isTrue()
        assertThat(dr.retry.maxAttempts).isEqualTo(5)
        assertThat(dr.retry.backoff).isEqualTo("linear")
        assertThat(dr.error.onFailure).isEqualTo("log_only")
        assertThat(dr.cleanupIntervalHours).isEqualTo(12)
    }

    @Test
    fun `config demo_recording platform overrides work`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf(
                    "enabled" to true,
                    "platform" to mapOf(
                        "web" to "playwright",
                        "terminal" to "script"
                    )
                )
            ),
            "."
        )
        assertThat(config.demoRecording.platform.web).isEqualTo("playwright")
        assertThat(config.demoRecording.platform.terminal).isEqualTo("script")
    }

    @Test
    fun `config demo_recording quality overrides work`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf(
                    "enabled" to true,
                    "quality" to mapOf(
                        "resolution" to "1920x1080",
                        "fps" to 30,
                        "codec" to "h264"
                    )
                )
            ),
            "."
        )
        assertThat(config.demoRecording.quality.resolution).isEqualTo("1920x1080")
        assertThat(config.demoRecording.quality.fps).isEqualTo(30)
        assertThat(config.demoRecording.quality.codec).isEqualTo("h264")
    }

    @Test
    fun `resolveRepoFullName regex matches valid GitHub SSH URL`() {
        val gitConfig = "[remote \"origin\"]\n\turl = git@github.com:owner/repo.git"
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
            .find(gitConfig, gitConfig.indexOf("[remote \"origin\"]"))
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("owner/repo")
    }

    @Test
    fun `resolveRepoFullName regex matches HTTPS GitHub URL`() {
        val gitConfig = "[remote \"origin\"]\n\turl = https://github.com/owner/project-name.git"
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
            .find(gitConfig, gitConfig.indexOf("[remote \"origin\"]"))
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("owner/project-name")
    }

    @Test
    fun `resolveRepoFullName regex returns null for non-GitHub URL`() {
        val gitConfig = "[remote \"origin\"]\n\turl = https://gitlab.com/owner/repo.git"
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
            .find(gitConfig, gitConfig.indexOf("[remote \"origin\"]"))
        assertThat(match).isNull()
    }

    @Test
    fun `resolveRepoFullName regex returns null for GitHub URL without dot git suffix`() {
        val gitConfig = "[remote \"origin\"]\n\turl = git@github.com:owner/repo"
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
            .find(gitConfig, gitConfig.indexOf("[remote \"origin\"]"))
        assertThat(match).isNull()
    }

    @Test
    fun `resolveRepoFullName regex matches GitHub HTTPS URL with hyphens`() {
        val gitConfig = "[remote \"origin\"]\n\turl = https://github.com/my-org/my-project.git"
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)""")
            .find(gitConfig, gitConfig.indexOf("[remote \"origin\"]"))
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("my-org/my-project")
    }

    @Test
    fun `resolveRepoFullName regex returns null when origin remote missing`() {
        val gitConfig = "[remote \"upstream\"]\n\turl = git@github.com:owner/repo.git"
        val originIdx = gitConfig.indexOf("[remote \"origin\"]")
        assertThat(originIdx).isEqualTo(-1)
    }

    @Test
    fun `resolveIssueId regex matches quoted issue_id`() {
        val content = """{"issue_id":"abc-123","status":"done"}"""
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("abc-123")
    }

    @Test
    fun `resolveIssueId regex returns null for missing issue_id`() {
        val content = """{"status":"done"}"""
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNull()
    }

    @Test
    fun `resolveIssueId regex matches issue_id in review exhausted file content`() {
        val content = """
            {"issue_id":"issue-uuid-99","attempts":3}
        """.trimIndent()
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("issue-uuid-99")
    }

    @Test
    fun `resolveIssueId regex returns null for empty content`() {
        val content = ""
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNull()
    }

    @Test
    fun `resolveIssueId regex matches issue_id with UUID format`() {
        val content = """{"issue_id":"550e8400-e29b-41d4-a716-446655440000"}"""
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("550e8400-e29b-41d4-a716-446655440000")
    }

    @Test
    fun `resolveIssueId regex matches issue_id with numeric value`() {
        val content = """{"issue_id":"12345"}"""
        val match = Regex(""""issue_id":"([^"]+)"""").find(content)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("12345")
    }

    @Test
    fun `parseRepoFullName regex matches Beans git config pattern`() {
        val gitConfig = "[remote \"origin\"]\n\turl = https://github.com/owner/repo.git\n"
        val originIdx = gitConfig.indexOf("[remote \"origin\"]")
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?\s*$""")
            .find(gitConfig, originIdx)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("owner/repo")
    }

    @Test
    fun `resolveRepoFullName reads github remote from git config`(@TempDir tmpDir: Path) {
        val gitDir = tmpDir.resolve(".git")
        Files.createDirectories(gitDir)
        Files.writeString(
            gitDir.resolve("config"),
            "[remote \"origin\"]\n\turl = git@github.com:acme/widget.git\n"
        )

        val result = invokeResolveRepoFullName(tmpDir)

        assertThat(result).isEqualTo("acme/widget")
    }

    @Test
    fun `resolveRepoFullName returns null when git config missing`(@TempDir tmpDir: Path) {
        assertThat(invokeResolveRepoFullName(tmpDir)).isNull()
    }

    @Test
    fun `resolveRepoFullName returns null when git config has no origin remote`(@TempDir tmpDir: Path) {
        val gitDir = tmpDir.resolve(".git")
        Files.createDirectories(gitDir)
        Files.writeString(
            gitDir.resolve("config"),
            "[remote \"upstream\"]\n\turl = git@github.com:acme/widget.git\n"
        )

        val result = invokeResolveRepoFullName(tmpDir)

        assertThat(result).isNull()
    }

    @Test
    fun `resolveRepoFullName returns null for git config without GitHub URL`(@TempDir tmpDir: Path) {
        val gitDir = tmpDir.resolve(".git")
        Files.createDirectories(gitDir)
        Files.writeString(
            gitDir.resolve("config"),
            "[remote \"origin\"]\n\turl = https://gitlab.com/org/project.git\n"
        )

        val result = invokeResolveRepoFullName(tmpDir)

        assertThat(result).isNull()
    }

    @Test
    fun `resolveRepoFullName handles malformed git config`(@TempDir tmpDir: Path) {
        val gitDir = tmpDir.resolve(".git")
        Files.createDirectories(gitDir)
        Files.writeString(
            gitDir.resolve("config"),
            "not a valid git config"
        )

        val result = invokeResolveRepoFullName(tmpDir)

        assertThat(result).isNull()
    }

    @Test
    fun `resolveIssueId reads issue_id from review exhausted file`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve(".review-exhausted"),
            """{"issue_id":"linear-uuid-42"}"""
        )

        val result = invokeResolveIssueId(tmpDir)

        assertThat(result).isEqualTo("linear-uuid-42")
    }

    @Test
    fun `resolveIssueId returns null when file missing`(@TempDir tmpDir: Path) {
        assertThat(invokeResolveIssueId(tmpDir)).isNull()
    }

    @Test
    fun `resolveIssueId returns null when file is empty`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve(".review-exhausted"), "")

        val result = invokeResolveIssueId(tmpDir)

        assertThat(result).isNull()
    }

    @Test
    fun `resolveIssueId returns null when file has no issue_id`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve(".review-exhausted"),
            """{"status":"done","attempts":3}"""
        )

        val result = invokeResolveIssueId(tmpDir)

        assertThat(result).isNull()
    }

    @Test
    fun `resolveIssueId reads issue_id from file with nested content`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve(".review-exhausted"),
            """{"issue_id":"fle-99","status":"exhausted","attempts":5,"last_error":"timeout"}"""
        )

        val result = invokeResolveIssueId(tmpDir)

        assertThat(result).isEqualTo("fle-99")
    }

    @Test
    fun `createDeployer returns configured deployer`() {
        val method = DemoRecordingTrigger::class.java.getDeclaredMethod(
            "createDeployer",
            com.flexsentlabs.koncerto.logging.StructuredLogger::class.java
        )
        method.isAccessible = true
        val deployer = method.invoke(
            DemoRecordingTrigger,
            com.flexsentlabs.koncerto.logging.StructuredLogger(emptyList())
        )
        assertThat(deployer).isNotNull()
        assertThat(deployer!!::class.java).isEqualTo(
            com.flexsentlabs.koncerto.deploy.TargetProjectDeployer::class.java
        )
    }

    @Test
    fun `config with only demo_recording enabled minimal`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf("enabled" to true)
            ),
            "."
        )
        assertThat(config.demoRecording.enabled).isTrue()
        assertThat(config.demoRecording.targetUrl).isEqualTo("")
        assertThat(config.demoRecording.retry.maxAttempts).isEqualTo(3)
        assertThat(config.demoRecording.retry.backoff).isEqualTo("exponential")
    }

    @Test
    fun `config demo_recording quality parses resolution into dimensions`() {
        val config = ServiceConfig.fromMap(
            mapOf(
                "poll_interval_ms" to 15000,
                "demo_recording" to mapOf(
                    "enabled" to true,
                    "quality" to mapOf("resolution" to "1920x1080")
                )
            ),
            "."
        )
        assertThat(config.demoRecording.quality.width).isEqualTo(1920)
        assertThat(config.demoRecording.quality.height).isEqualTo(1080)
    }

    private fun invokeResolveRepoFullName(workspacePath: Path): String? {
        val method = DemoRecordingTrigger::class.java.getDeclaredMethod(
            "resolveRepoFullName",
            Path::class.java,
            ServiceConfig::class.java
        )
        method.isAccessible = true
        val config = ServiceConfig.fromMap(mapOf("poll_interval_ms" to 15000), ".")
        return method.invoke(DemoRecordingTrigger, workspacePath, config) as String?
    }

    private fun invokeResolveIssueId(workspacePath: Path): String? {
        val method = DemoRecordingTrigger::class.java.getDeclaredMethod(
            "resolveIssueId",
            Path::class.java,
            com.flexsentlabs.koncerto.logging.StructuredLogger::class.java
        )
        method.isAccessible = true
        return method.invoke(
            DemoRecordingTrigger,
            workspacePath,
            com.flexsentlabs.koncerto.logging.StructuredLogger(emptyList())
        ) as String?
    }
}
