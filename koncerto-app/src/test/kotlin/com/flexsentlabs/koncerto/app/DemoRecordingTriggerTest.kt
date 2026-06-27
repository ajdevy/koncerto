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
    fun `resolveRepoFullName regex matches valid GitHub URL`() {
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
    fun `parseRepoFullName regex matches Beans git config pattern`() {
        val gitConfig = "[remote \"origin\"]\n\turl = https://github.com/owner/repo.git\n"
        val originIdx = gitConfig.indexOf("[remote \"origin\"]")
        val match = Regex("""url\s*=\s*.+github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?\s*$""")
            .find(gitConfig, originIdx)
        assertThat(match).isNotNull()
        assertThat(match!!.groupValues[1]).isEqualTo("owner/repo")
    }

    @Test
    fun `resolveRepoFullName reads github remote from git config`(@org.junit.jupiter.api.io.TempDir tmpDir: java.nio.file.Path) {
        val gitDir = tmpDir.resolve(".git")
        java.nio.file.Files.createDirectories(gitDir)
        java.nio.file.Files.writeString(
            gitDir.resolve("config"),
            "[remote \"origin\"]\n\turl = git@github.com:acme/widget.git\n"
        )

        val result = invokeResolveRepoFullName(tmpDir)

        assertThat(result).isEqualTo("acme/widget")
    }

    @Test
    fun `resolveRepoFullName returns null when git config missing`(@org.junit.jupiter.api.io.TempDir tmpDir: java.nio.file.Path) {
        assertThat(invokeResolveRepoFullName(tmpDir)).isNull()
    }

    @Test
    fun `resolveIssueId reads issue_id from review exhausted file`(@org.junit.jupiter.api.io.TempDir tmpDir: java.nio.file.Path) {
        java.nio.file.Files.writeString(
            tmpDir.resolve(".review-exhausted"),
            """{"issue_id":"linear-uuid-42"}"""
        )

        val result = invokeResolveIssueId(tmpDir)

        assertThat(result).isEqualTo("linear-uuid-42")
    }

    @Test
    fun `resolveIssueId returns null when file missing`(@org.junit.jupiter.api.io.TempDir tmpDir: java.nio.file.Path) {
        assertThat(invokeResolveIssueId(tmpDir)).isNull()
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

    private fun invokeResolveRepoFullName(workspacePath: java.nio.file.Path): String? {
        val method = DemoRecordingTrigger::class.java.getDeclaredMethod(
            "resolveRepoFullName",
            java.nio.file.Path::class.java,
            com.flexsentlabs.koncerto.core.config.ServiceConfig::class.java
        )
        method.isAccessible = true
        val config = ServiceConfig.fromMap(mapOf("poll_interval_ms" to 15000), ".")
        return method.invoke(DemoRecordingTrigger, workspacePath, config) as String?
    }

    private fun invokeResolveIssueId(workspacePath: java.nio.file.Path): String? {
        val method = DemoRecordingTrigger::class.java.getDeclaredMethod(
            "resolveIssueId",
            java.nio.file.Path::class.java,
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
