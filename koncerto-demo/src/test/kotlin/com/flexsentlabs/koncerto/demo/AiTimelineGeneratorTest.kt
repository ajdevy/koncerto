package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoStatus
import com.flexsentlabs.koncerto.demo.model.DemoTask
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.report.AiTimelineGenerator
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AiTimelineGeneratorTest {

    private val task = DemoTask(
        id = "task-1", issueId = "issue-1", issueIdentifier = "KONC-123",
        projectSlug = "test", platform = DemoPlatform.PLAYWRIGHT,
        status = DemoStatus.COMPLETED, trigger = DemoTrigger.REVIEW_PASSED,
        durationMs = 5000, fileSizeBytes = 1024,
        recordingUrl = "https://example.com/demo.webm",
        createdAt = "2026-06-21T00:00:00Z", updatedAt = "2026-06-21T00:01:00Z",
        completedAt = "2026-06-21T00:01:00Z"
    )

    @Test
    fun `generateTimeline returns default events when no endpoint configured`() = runTest {
        val generator = AiTimelineGenerator()
        val result = generator.generateTimeline(task)
        assert(result is DemoResult.Success)
        val events = (result as DemoResult.Success).value
        assert(events.size == 2)
        assert(events[0].timestamp == "0:00")
        assert(events[0].description == "Recording started")
        assert(events[1].description == "Recording completed")
    }

    @Test
    fun `generateReproSteps returns default steps when no endpoint configured`() = runTest {
        val generator = AiTimelineGenerator()
        val result = generator.generateReproSteps(task)
        assert(result is DemoResult.Success)
        val steps = (result as DemoResult.Success).value
        assert(steps.size == 3)
        assert(steps.all { it.isNotBlank() })
    }

    @Test
    fun `generateTimeline returns defaults when API call fails`() = runTest {
        val generator = AiTimelineGenerator(apiEndpoint = "http://localhost:1/invalid")
        val result = generator.generateTimeline(task)
        assert(result is DemoResult.Success)
        val events = (result as DemoResult.Success).value
        assert(events.size == 2)
        assert(events[0].description == "Recording started")
    }

    @Test
    fun `generateReproSteps returns defaults when API call fails`() = runTest {
        val generator = AiTimelineGenerator(apiEndpoint = "http://localhost:1/invalid")
        val result = generator.generateReproSteps(task)
        assert(result is DemoResult.Success)
        val steps = (result as DemoResult.Success).value
        assert(steps.size == 3)
    }

    @Test
    fun `TimelineEvent data class holds timestamp and description`() {
        val event = AiTimelineGenerator.TimelineEvent("1:30", "User logged in")
        assert(event.timestamp == "1:30")
        assert(event.description == "User logged in")
    }

    @Test
    fun `generateTimeline parses successful API response`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/timeline") { exchange ->
            val body = """{"choices":[{"message":{"content":"```json\n[{\"timestamp\":\"0:05\",\"description\":\"Clicked checkout\"}]\n```"}}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val port = server.address.port
            val generator = AiTimelineGenerator(
                apiEndpoint = "http://127.0.0.1:$port/timeline",
                apiKey = "test-key"
            )
            val result = generator.generateTimeline(task)
            assert(result is DemoResult.Success)
            val events = (result as DemoResult.Success).value
            assert(events.size == 1)
            assert(events[0].description == "Clicked checkout")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `generateReproSteps parses successful API response`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/steps") { exchange ->
            val body = """{"choices":[{"message":{"content":"```json\n[\"Open app\",\"Click checkout\"]\n```"}}]}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val port = server.address.port
            val generator = AiTimelineGenerator(apiEndpoint = "http://127.0.0.1:$port/steps")
            val result = generator.generateReproSteps(task)
            assert(result is DemoResult.Success)
            val steps = (result as DemoResult.Success).value
            assert(steps.contains("Open app"))
            assert(steps.contains("Click checkout"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `generateTimeline falls back when API returns non-2xx`() = runTest {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/fail") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }
        server.start()
        try {
            val port = server.address.port
            val generator = AiTimelineGenerator(apiEndpoint = "http://127.0.0.1:$port/fail")
            val result = generator.generateTimeline(task.copy(durationMs = 120_000))
            assert(result is DemoResult.Success)
            val events = (result as DemoResult.Success).value
            assert(events[1].timestamp == "2m 0s")
        } finally {
            server.stop(0)
        }
    }
}
