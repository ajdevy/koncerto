package com.flexsentlabs.koncerto.demo.config

data class DemoConfig(
    val enabled: Boolean = false,
    val tempDir: String = "/tmp/koncerto-demo",
    val targetUrl: String = "",
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 5_000L,
    val preflightTimeoutMs: Long = 10_000L,
    val r2: R2Config? = null,
    val retentionDays: Int = 90,
    val maxRecordingsPerSpace: Int = 100,
    val defaultPlatform: String = "playwright",
    val ai: AiConfig? = null,
    val cleanupIntervalHours: Int = 24
) {
    data class R2Config(
        val endpoint: String,
        val accessKey: String,
        val secretKey: String,
        val bucketName: String,
        val publicUrlBase: String,
        val presignedUrlTtlSeconds: Long = 315360000,
        val region: String = "auto"
    )

    data class AiConfig(
        val endpoint: String = "",
        val apiKey: String = "",
        val model: String = "free",
        val timelineEnabled: Boolean = false,
        val reproStepsEnabled: Boolean = false
    )
}
