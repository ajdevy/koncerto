package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.report.AiTimelineGenerator
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class DemoReportGeneratorTest {

    private val generator = DemoReportGenerator()

    private fun createTask(
        id: String = "task-1",
        issueId: String = "issue-1",
        issueIdentifier: String = "KONC-123",
        platform: DemoPlatform = DemoPlatform.PLAYWRIGHT,
        status: DemoStatus = DemoStatus.COMPLETED,
        durationMs: Long? = 5000L,
        fileSizeBytes: Long? = 2048L,
        completedAt: String? = "2026-01-01T00:01:05Z",
        trigger: DemoTrigger = DemoTrigger.MANUAL
    ): DemoTask = DemoTask(
        id = id, issueId = issueId, issueIdentifier = issueIdentifier,
        projectSlug = null, platform = platform, status = status,
        trigger = trigger, createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:01:05Z", completedAt = completedAt,
        durationMs = durationMs, fileSizeBytes = fileSizeBytes
    )

    @Test
    fun `generates HTML successfully`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        try {
            val result = generator.generateHtmlReport(task, "https://example.com/recording.webm", outputFile)
            assert(result is DemoResult.Success)
            assert((result as DemoResult.Success).value == outputFile)
            assert(outputFile.exists())
            assert(outputFile.length() > 0)
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `HTML contains issueIdentifier`() = runTest {
        val task = createTask(issueIdentifier = "KONC-456")
        val outputFile = File.createTempFile("demo-report-", ".html")
        try {
            val result = generator.generateHtmlReport(task, "https://example.com/recording.webm", outputFile)
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("KONC-456"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `HTML contains recordingUrl`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        try {
            val result = generator.generateHtmlReport(task, "https://cdn.example.com/video.webm", outputFile)
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("https://cdn.example.com/video.webm"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `uses default timeline and steps when not provided`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        try {
            val result = generator.generateHtmlReport(task, "https://example.com/recording.webm", outputFile)
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("0:00"))
            assert(html.contains("Recording started"))
            assert(html.contains("Review the demo recording above"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `uses custom timeline events when provided`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        val timelineEvents = listOf(
            AiTimelineGenerator.TimelineEvent("0:05", "User clicked login"),
            AiTimelineGenerator.TimelineEvent("0:10", "Dashboard loaded"),
            AiTimelineGenerator.TimelineEvent("0:15", "Form submitted")
        )
        try {
            val result = generator.generateHtmlReport(
                task, "https://example.com/recording.webm", outputFile,
                timelineEvents = timelineEvents
            )
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("User clicked login"))
            assert(html.contains("Dashboard loaded"))
            assert(html.contains("Form submitted"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `uses custom repro steps when provided`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        val reproSteps = listOf(
            "Open the application",
            "Navigate to the settings page",
            "Toggle the dark mode switch"
        )
        try {
            val result = generator.generateHtmlReport(
                task, "https://example.com/recording.webm", outputFile,
                reproSteps = reproSteps
            )
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("Open the application"))
            assert(html.contains("Navigate to the settings page"))
            assert(html.contains("Toggle the dark mode switch"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `uses both custom timeline and repro steps when provided`() = runTest {
        val task = createTask()
        val outputFile = File.createTempFile("demo-report-", ".html")
        val timelineEvents = listOf(
            AiTimelineGenerator.TimelineEvent("0:00", "App launched")
        )
        val reproSteps = listOf("Step one")
        try {
            val result = generator.generateHtmlReport(
                task, "https://example.com/recording.webm", outputFile,
                timelineEvents = timelineEvents, reproSteps = reproSteps
            )
            assert(result is DemoResult.Success)
            val html = outputFile.readText()
            assert(html.contains("App launched"))
            assert(html.contains("Step one"))
        } finally {
            outputFile.delete()
        }
    }

    @Test
    fun `handles exceptions gracefully`() = runTest {
        val task = createTask()
        val parentFile = File.createTempFile("report-parent-", ".tmp")
        try {
            val childFile = File(parentFile, "subdir/report.html")
            val result = generator.generateHtmlReport(task, "https://example.com/recording.webm", childFile)
            assert(result is DemoResult.Failure)
            assert((result as DemoResult.Failure).error is DemoError.ReportFailed)
        } finally {
            parentFile.delete()
        }
    }
}
