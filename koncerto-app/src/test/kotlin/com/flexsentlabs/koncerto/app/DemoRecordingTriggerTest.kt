package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
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
}
