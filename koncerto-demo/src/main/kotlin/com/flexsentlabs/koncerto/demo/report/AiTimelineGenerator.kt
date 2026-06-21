package com.flexsentlabs.koncerto.demo.report

import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class AiTimelineGenerator(
    private val apiEndpoint: String = "",
    private val apiKey: String = "",
    private val model: String = "free"
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    suspend fun generateTimeline(task: DemoTask): DemoResult<List<TimelineEvent>> {
        if (apiEndpoint.isBlank()) return DemoResult.Success(defaultTimeline(task))
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = buildRequestBody(task, "timeline",
                    "Generate a concise timeline of events for this demo recording. " +
                    "Return a JSON array of {timestamp, description} objects."
                )
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiEndpoint))
                    .header("Content-Type", "application/json")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val events = parseTimelineResponse(response.body())
                    if (events.isNotEmpty()) DemoResult.Success(events)
                    else DemoResult.Success(defaultTimeline(task))
                } else {
                    DemoResult.Success(defaultTimeline(task))
                }
            } catch (e: Exception) {
                DemoResult.Success(defaultTimeline(task))
            }
        }
    }

    suspend fun generateReproSteps(task: DemoTask): DemoResult<List<String>> {
        if (apiEndpoint.isBlank()) return DemoResult.Success(defaultSteps(task))
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = buildRequestBody(task, "repro-steps",
                    "Generate step-by-step reproduction instructions for this demo. " +
                    "Return a JSON array of strings."
                )
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(apiEndpoint))
                    .header("Content-Type", "application/json")
                    .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() in 200..299) {
                    val steps = parseStepsResponse(response.body())
                    if (steps.isNotEmpty()) DemoResult.Success(steps)
                    else DemoResult.Success(defaultSteps(task))
                } else {
                    DemoResult.Success(defaultSteps(task))
                }
            } catch (e: Exception) {
                DemoResult.Success(defaultSteps(task))
            }
        }
    }

    private fun buildRequestBody(task: DemoTask, feature: String, systemPrompt: String): String {
        return """{
  "model": "$model",
  "messages": [
    {"role": "system", "content": "$systemPrompt"},
    {"role": "user", "content": "Task: ${task.issueIdentifier}\nPlatform: ${task.platform.name}\nDuration: ${task.durationMs ?: "unknown"}ms"}
  ],
  "stream": false
}"""
    }

    private fun parseTimelineResponse(body: String): List<TimelineEvent> {
        val content = extractContent(body)
        val json = content.substringAfter("```json", content)
            .substringBefore("```")
            .trim()
        val events = mutableListOf<TimelineEvent>()
        val eventRegex = Regex(""""timestamp"\s*:\s*"([^"]+)"\s*,\s*"description"\s*:\s*"([^"]+)"""")
        eventRegex.findAll(json).forEach { match ->
            events.add(TimelineEvent(match.groupValues[1], match.groupValues[2]))
        }
        return events
    }

    private fun parseStepsResponse(body: String): List<String> {
        val content = extractContent(body)
        val json = content.substringAfter("```json", content)
            .substringBefore("```")
            .trim()
        val steps = mutableListOf<String>()
        val stepRegex = Regex(""""([^"]+)"""")
        stepRegex.findAll(json).forEach { match ->
            steps.add(match.groupValues[1])
        }
        return steps.filter { it.isNotBlank() }
    }

    private fun extractContent(body: String): String {
        val contentRegex = Regex(""""content"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return contentRegex.find(body)?.groupValues?.getOrNull(1)?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: body
    }

    private fun defaultTimeline(task: DemoTask): List<TimelineEvent> = listOf(
        TimelineEvent("0:00", "Recording started"),
        TimelineEvent(taskDurationLabel(task.durationMs), "Recording completed")
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

    data class TimelineEvent(
        val timestamp: String,
        val description: String
    )
}
