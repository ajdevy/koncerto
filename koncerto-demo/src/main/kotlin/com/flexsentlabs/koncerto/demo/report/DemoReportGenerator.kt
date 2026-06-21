package com.flexsentlabs.koncerto.demo.report

import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTask
import java.io.File
import java.nio.file.Files

class DemoReportGenerator {

    suspend fun generateHtmlReport(
        task: DemoTask, recordingUrl: String, outputFile: File,
        timelineEvents: List<AiTimelineGenerator.TimelineEvent>? = null,
        reproSteps: List<String>? = null
    ): DemoResult<File> {
        return try {
            val events = timelineEvents ?: defaultTimeline(task)
            val steps = reproSteps ?: defaultSteps(task)
            val html = buildReportHtml(task, recordingUrl, events, steps)
            Files.writeString(outputFile.toPath(), html)
            DemoResult.Success(outputFile)
        } catch (e: Exception) {
            DemoResult.Failure(
                com.flexsentlabs.koncerto.demo.model.DemoError.ReportFailed(
                    RuntimeException("html_report_generation: ${e.message}", e)
                )
            )
        }
    }

    private fun buildReportHtml(
        task: DemoTask, recordingUrl: String,
        timelineEvents: List<AiTimelineGenerator.TimelineEvent>,
        reproSteps: List<String>
    ): String {
        val platformLabel = task.platform.name.lowercase().replaceFirstChar { it.uppercase() }
        val durationLabel = task.durationMs?.let { ms ->
            val sec = ms / 1000
            "${sec / 60}m ${sec % 60}s"
        } ?: "N/A"
        val sizeLabel = task.fileSizeBytes?.let { bytes ->
            when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                else -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
            }
        } ?: "N/A"
        val dateLabel = task.completedAt?.substringBefore("T") ?: task.createdAt.substringBefore("T")
        val timelineHtml = timelineEvents.joinToString("\n") { event ->
            """                    <div class="timeline-item">
                        <span class="timestamp">${event.timestamp}</span>
                        <span>${event.description}</span>
                    </div>"""
        }
        val stepsHtml = reproSteps.joinToString("\n") { step ->
            """                    <div class="step">$step</div>"""
        }

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Demo Recording — ${task.issueIdentifier}</title>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                       background: #f5f5f5; color: #333; line-height: 1.6; }
                .container { max-width: 960px; margin: 0 auto; padding: 20px; }
                header { background: #fff; border-radius: 8px; padding: 24px; margin-bottom: 20px;
                         box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                h1 { font-size: 1.5em; margin-bottom: 8px; color: #1a1a2e; }
                .meta { display: flex; gap: 16px; flex-wrap: wrap; font-size: 0.9em; color: #666; }
                .meta span { background: #f0f0f5; padding: 4px 12px; border-radius: 12px; }
                .video-container { background: #000; border-radius: 8px; overflow: hidden; margin-bottom: 20px; }
                video { width: 100%; max-height: 540px; display: block; }
                .timeline { background: #fff; border-radius: 8px; padding: 24px; margin-bottom: 20px;
                            box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                .timeline h2 { font-size: 1.1em; margin-bottom: 12px; color: #1a1a2e; }
                .timeline-item { padding: 8px 0; border-bottom: 1px solid #eee;
                                 display: flex; gap: 12px; font-size: 0.9em; }
                .timeline-item:last-child { border-bottom: none; }
                .timestamp { color: #888; font-family: monospace; white-space: nowrap; min-width: 80px; }
                .steps { background: #fff; border-radius: 8px; padding: 24px; margin-bottom: 20px;
                         box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                .steps h2 { font-size: 1.1em; margin-bottom: 12px; color: #1a1a2e; }
                .step { padding: 8px 0; border-bottom: 1px solid #eee; counter-increment: step; }
                .step:last-child { border-bottom: none; }
                .step::before { content: counter(step) ". "; font-weight: bold; color: #6c63ff; }
                .logs { background: #1a1a2e; border-radius: 8px; padding: 16px; margin-bottom: 20px; }
                .logs h2 { font-size: 1.1em; margin-bottom: 12px; color: #fff; }
                .log-entry { font-family: monospace; font-size: 0.85em; color: #a0ffa0;
                             padding: 4px 0; white-space: pre-wrap; word-break: break-all; }
            </style>
        </head>
        <body>
            <div class="container">
                <header>
                    <h1>Demo Recording: ${task.issueIdentifier}</h1>
                    <p style="color:#666;margin-bottom:12px;">${task.issueId}</p>
                    <div class="meta">
                        <span>${platformLabel}</span>
                        <span>${durationLabel}</span>
                        <span>${sizeLabel}</span>
                        <span>$dateLabel</span>
                        <span>${task.trigger.name.lowercase().replaceFirstChar { it.uppercase() }}</span>
                    </div>
                </header>

                <div class="video-container">
                    <video controls preload="metadata">
                        <source src="$recordingUrl" type="video/webm">
                        Your browser does not support the video tag.
                    </video>
                </div>

                <div class="timeline">
                    <h2>Timeline</h2>
                    $timelineHtml
                </div>

                <div class="steps">
                    <h2>Reproduction Steps</h2>
                    $stepsHtml
                </div>

                <div class="logs">
                    <h2>Recording Details</h2>
                    <div class="log-entry">[INFO] Platform: ${task.platform.name}</div>
                    <div class="log-entry">[INFO] Duration: $durationLabel</div>
                    <div class="log-entry">[INFO] File size: $sizeLabel</div>
                    <div class="log-entry">[INFO] Trigger: ${task.trigger.name}</div>
                    <div class="log-entry">[INFO] Task ID: ${task.id}</div>
                    <div class="log-entry">[INFO] ${task.fallbackFrom?.let { "Fallback from: $it" } ?: "Primary recording"}</div>
                </div>
            </div>
        </body>
        </html>
        """.trimIndent()
    }

    private fun defaultTimeline(task: DemoTask): List<AiTimelineGenerator.TimelineEvent> = listOf(
        AiTimelineGenerator.TimelineEvent("0:00", "Recording started"),
        AiTimelineGenerator.TimelineEvent(taskDurationLabel(task.durationMs), "Recording completed")
    )

    private fun defaultSteps(task: DemoTask): List<String> = listOf(
        "Review the demo recording above",
        "Verify the expected behavior matches the recording",
        "Check the timeline for key events"
    )

    private fun taskDurationLabel(ms: Long?): String {
        if (ms == null) return "N/A"
        val sec = ms / 1000
        return "${sec / 60}m ${sec % 60}s"
    }
}
