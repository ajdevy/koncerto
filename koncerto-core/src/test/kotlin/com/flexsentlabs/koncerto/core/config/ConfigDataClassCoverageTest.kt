package com.flexsentlabs.koncerto.core.config

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import com.flexsentlabs.koncerto.core.config.EmailConfig
import com.flexsentlabs.koncerto.core.config.WebhookConfig
import com.flexsentlabs.koncerto.core.agent.FallbackProviderConfig
import com.flexsentlabs.koncerto.core.errors.ErrorRecord
import com.flexsentlabs.koncerto.core.events.AgentLifecycleEvent
import org.junit.jupiter.api.Test

class ConfigDataClassCoverageTest {

    @Test
    fun `nested config data classes construct with explicit values`() {
        val followUp = FollowUpConfig(
            titleTemplate = "Review {{ issue.title }}",
            state = "Todo",
            descriptionTemplate = "desc",
            labels = listOf("auto"),
            linkType = "blocks",
            assignee = "creator",
            agent = "fast"
        )
        val crossProject = CrossProjectFollowUpConfig(
            targetProjectSlug = "other",
            titleTemplate = "Cross {{ issue.title }}",
            descriptionTemplate = "cross desc",
            linkType = "relates"
        )
        val stage = StageAgentConfig(
            prompt = "implement",
            model = "gpt-4o",
            effort = "high",
            maxConcurrent = 2,
            agentKind = "codex",
            command = "codex",
            onCompleteState = "In Review",
            onFailureState = "Blocked",
            maxReviewAttempts = 2,
            agent = "fast",
            followUp = followUp,
            crossProjectFollowUp = crossProject
        )
        val provider = AgentProviderConfig(
            kind = "codex",
            command = "codex",
            model = "gpt-4o",
            effort = "medium",
            maxConcurrent = 1
        )
        val rateLimiter = RateLimiterConfig(requestsPerSecond = 5, maxBurst = 10)
        val circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, resetTimeoutMs = 60_000)
        val docker = DockerConfig(
            enabled = true,
            image = "koncerto/agent:latest",
            cpu = "2",
            memory = "4g",
            network = true
        )
        val storage = DemoRecordingConfig.StorageConfig(
            r2Endpoint = "https://r2.example.com",
            r2Bucket = "demos",
            r2AccessKey = "key",
            r2SecretKey = "secret",
            publicUrlBase = "https://cdn.example.com",
            presignedUrlTtl = 3600,
            region = "auto"
        )
        val subtaskState = SubtaskState(
            def = SubtaskDef(id = "s1", description = "Step", prompt = "Do"),
            status = SubtaskStatus.RUNNING,
            branchName = "subtask/KONC-1/s1",
            runId = "run-1"
        )
        val fallback = FallbackProviderConfig(
            primaryProvider = "codex",
            fallbackProviders = listOf("claude"),
            fallbackOnFailure = true,
            fallbackOnTimeout = false
        )
        val failed = AgentLifecycleEvent.Failed(
            agentKey = "agent-1",
            error = "boom",
            attempt = 2,
            timestamp = 1_700_000_000_000L
        )

        assertThat(stage.followUp?.titleTemplate).isEqualTo("Review {{ issue.title }}")
        assertThat(stage.crossProjectFollowUp?.targetProjectSlug).isEqualTo("other")
        assertThat(provider.model).isEqualTo("gpt-4o")
        assertThat(rateLimiter.maxBurst).isEqualTo(10)
        assertThat(circuitBreaker.failureThreshold).isEqualTo(3)
        assertThat(docker.image).isEqualTo("koncerto/agent:latest")
        assertThat(storage.r2Bucket).isEqualTo("demos")
        assertThat(subtaskState.def.id).isEqualTo("s1")
        assertThat(fallback.fallbackProviders).isEqualTo(listOf("claude"))
        assertThat(failed.error).isEqualTo("boom")
        assertThat(failed.agentKey).isEqualTo("agent-1")

        val email = EmailConfig(
            smtpHost = "smtp.example.com",
            username = "user",
            password = "pass",
            from = "from@example.com",
            to = "to@example.com"
        )
        val webhook = WebhookConfig(url = "https://example.com/hook", headers = mapOf("X-Test" to "1"))
        val errorRecord = ErrorRecord(
            key = "dispatch",
            error = "failed",
            count = 1,
            firstSeen = 1L,
            lastSeen = 2L,
            category = "runtime"
        )

        assertThat(email.smtpHost).isEqualTo("smtp.example.com")
        assertThat(webhook.headers["X-Test"]).isEqualTo("1")
        assertThat(errorRecord.error).isEqualTo("failed")
        assertThat(errorRecord.copy(count = 99).count).isEqualTo(99)

        assertThat(rateLimiter.copy(maxBurst = 11).maxBurst).isEqualTo(11)
        assertThat(circuitBreaker.copy(failureThreshold = 4).failureThreshold).isEqualTo(4)
        assertThat(followUp.copy(state = "Done").state).isEqualTo("Done")
        assertThat(crossProject.copy(linkType = "related").linkType).isEqualTo("related")
        assertThat(provider.copy(model = "gpt-5").model).isEqualTo("gpt-5")
        assertThat(storage.copy(r2Bucket = "other").r2Bucket).isEqualTo("other")
        assertThat(webhook.copy(url = "https://other.example/hook").url).isEqualTo("https://other.example/hook")
        assertThat(fallback.copy(primaryProvider = "claude").primaryProvider).isEqualTo("claude")
        assertThat(SubtaskStatus.values().toList()).contains(SubtaskStatus.RUNNING)
    }
}
