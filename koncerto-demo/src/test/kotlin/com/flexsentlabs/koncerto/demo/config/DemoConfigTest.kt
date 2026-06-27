package com.flexsentlabs.koncerto.demo.config

import org.junit.jupiter.api.Test

class DemoConfigTest {

    @Test
    fun `default DemoConfig values`() {
        val config = DemoConfig()
        assert(config.enabled == false)
        assert(config.tempDir == "/tmp/koncerto-demo")
        assert(config.targetUrl == "")
        assert(config.maxRetries == 3)
        assert(config.retryDelayMs == 5_000L)
        assert(config.preflightTimeoutMs == 10_000L)
        assert(config.r2 == null)
        assert(config.retentionDays == 90)
        assert(config.maxRecordingsPerSpace == 100)
        assert(config.defaultPlatform == "playwright")
        assert(config.ai == null)
        assert(config.cleanupIntervalHours == 24)
    }

    @Test
    fun `custom DemoConfig values`() {
        val config = DemoConfig(
            enabled = true,
            tempDir = "/custom/tmp",
            targetUrl = "https://example.com",
            maxRetries = 5,
            retryDelayMs = 10_000L,
            preflightTimeoutMs = 30_000L,
            retentionDays = 30,
            maxRecordingsPerSpace = 200,
            defaultPlatform = "asciinema",
            cleanupIntervalHours = 12
        )
        assert(config.enabled)
        assert(config.tempDir == "/custom/tmp")
        assert(config.targetUrl == "https://example.com")
        assert(config.maxRetries == 5)
        assert(config.retryDelayMs == 10_000L)
        assert(config.preflightTimeoutMs == 30_000L)
        assert(config.retentionDays == 30)
        assert(config.maxRecordingsPerSpace == 200)
        assert(config.defaultPlatform == "asciinema")
        assert(config.cleanupIntervalHours == 12)
    }

    @Test
    fun `DemoConfig copy preserves unmodified fields`() {
        val original = DemoConfig(enabled = true, maxRetries = 5)
        val copied = original.copy(maxRetries = 3)
        assert(copied.enabled)
        assert(copied.maxRetries == 3)
        assert(copied.tempDir == original.tempDir)
        assert(copied.retentionDays == original.retentionDays)
    }

    @Test
    fun `DemoConfig structural equality`() {
        val a = DemoConfig(enabled = true, maxRetries = 2)
        val b = DemoConfig(enabled = true, maxRetries = 2)
        assert(a == b)
        assert(a.hashCode() == b.hashCode())
    }

    @Test
    fun `DemoConfig inequality when fields differ`() {
        val a = DemoConfig(enabled = true)
        val b = DemoConfig(enabled = false)
        assert(a != b)
    }

    @Test
    fun `R2Config default values`() {
        val r2 = DemoConfig.R2Config(
            endpoint = "https://r2.example.com",
            accessKey = "ak",
            secretKey = "sk",
            bucketName = "bucket",
            publicUrlBase = "https://pub.example.com"
        )
        assert(r2.endpoint == "https://r2.example.com")
        assert(r2.accessKey == "ak")
        assert(r2.secretKey == "sk")
        assert(r2.bucketName == "bucket")
        assert(r2.publicUrlBase == "https://pub.example.com")
        assert(r2.presignedUrlTtlSeconds == 604800L)
        assert(r2.region == "auto")
    }

    @Test
    fun `R2Config custom values`() {
        val r2 = DemoConfig.R2Config(
            endpoint = "https://custom.example.com",
            accessKey = "custom-ak",
            secretKey = "custom-sk",
            bucketName = "custom-bucket",
            publicUrlBase = "https://custom.example.com",
            presignedUrlTtlSeconds = 3600L,
            region = "us-east-1"
        )
        assert(r2.presignedUrlTtlSeconds == 3600L)
        assert(r2.region == "us-east-1")
    }

    @Test
    fun `R2Config structural equality`() {
        val a = DemoConfig.R2Config("ep", "ak", "sk", "bucket", "pub", 3600L, "us-east-1")
        val b = DemoConfig.R2Config("ep", "ak", "sk", "bucket", "pub", 3600L, "us-east-1")
        assert(a == b)
        assert(a.hashCode() == b.hashCode())
    }

    @Test
    fun `R2Config inequality when fields differ`() {
        val a = DemoConfig.R2Config("ep", "ak", "sk", "bucket", "pub")
        val b = DemoConfig.R2Config("ep", "ak", "sk", "other-bucket", "pub")
        assert(a != b)
    }

    @Test
    fun `AiConfig default values`() {
        val ai = DemoConfig.AiConfig()
        assert(ai.endpoint == "")
        assert(ai.apiKey == "")
        assert(ai.model == "free")
        assert(ai.timelineEnabled == false)
        assert(ai.reproStepsEnabled == false)
    }

    @Test
    fun `AiConfig custom values`() {
        val ai = DemoConfig.AiConfig(
            endpoint = "https://ai.example.com",
            apiKey = "test-key",
            model = "gpt-4",
            timelineEnabled = true,
            reproStepsEnabled = true
        )
        assert(ai.endpoint == "https://ai.example.com")
        assert(ai.apiKey == "test-key")
        assert(ai.model == "gpt-4")
        assert(ai.timelineEnabled)
        assert(ai.reproStepsEnabled)
    }

    @Test
    fun `AiConfig structural equality`() {
        val a = DemoConfig.AiConfig("ep", "key", "model", true, false)
        val b = DemoConfig.AiConfig("ep", "key", "model", true, false)
        assert(a == b)
        assert(a.hashCode() == b.hashCode())
    }

    @Test
    fun `AiConfig inequality when fields differ`() {
        val a = DemoConfig.AiConfig(timelineEnabled = true)
        val b = DemoConfig.AiConfig(timelineEnabled = false)
        assert(a != b)
    }

    @Test
    fun `DemoConfig with nested R2Config`() {
        val r2 = DemoConfig.R2Config("ep", "ak", "sk", "bucket", "pub")
        val config = DemoConfig(enabled = true, r2 = r2)
        assert(config.r2 != null)
        assert(config.r2!!.bucketName == "bucket")
    }

    @Test
    fun `DemoConfig with nested AiConfig`() {
        val ai = DemoConfig.AiConfig(timelineEnabled = true, reproStepsEnabled = true)
        val config = DemoConfig(enabled = true, ai = ai)
        assert(config.ai != null)
        assert(config.ai!!.timelineEnabled)
        assert(config.ai!!.reproStepsEnabled)
    }

    @Test
    fun `DemoConfig with both R2 and Ai configs`() {
        val r2 = DemoConfig.R2Config("ep", "ak", "sk", "bucket", "pub")
        val ai = DemoConfig.AiConfig(model = "gpt-4")
        val config = DemoConfig(enabled = true, r2 = r2, ai = ai)
        assert(config.r2 != null)
        assert(config.ai != null)
        assert(config.ai!!.model == "gpt-4")
    }

    @Test
    fun `DemoConfig zero and edge values`() {
        val config = DemoConfig(
            maxRetries = 0,
            retryDelayMs = 0L,
            preflightTimeoutMs = 0L,
            retentionDays = 0,
            maxRecordingsPerSpace = 0,
            cleanupIntervalHours = 0
        )
        assert(config.maxRetries == 0)
        assert(config.retryDelayMs == 0L)
        assert(config.retentionDays == 0)
        assert(config.maxRecordingsPerSpace == 0)
    }
}
