package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.config.DemoConfig
import com.flexsentlabs.koncerto.demo.observability.DemoAuditLogger
import com.flexsentlabs.koncerto.demo.observability.DemoMetricsRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import com.flexsentlabs.koncerto.demo.report.DemoReportGenerator
import com.flexsentlabs.koncerto.demo.service.DemoCleanupScheduler
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DemoCleanupSchedulerTest {

    private lateinit var service: DemoRecordingService

    @BeforeEach
    fun setUp() {
        service = DemoRecordingService(
            config = DemoConfig(
                enabled = true,
                tempDir = System.getProperty("java.io.tmpdir"),
                maxRetries = 2,
                retryDelayMs = 1,
                retentionDays = 90,
                maxRecordingsPerSpace = 100,
                defaultPlatform = "playwright"
            ),
            taskRepository = FakeDemoTaskRepository(),
            recorderFactory = RecorderFactory(listOf(FakeRecorder2())),
            storage = FakeDemoStorage2(),
            reporter = FakeDemoReporter2(),
            reportGenerator = DemoReportGenerator(),
            metrics = DemoMetricsRecorder(),
            auditLogger = DemoAuditLogger("/tmp/test-cleanup-scheduler.log")
        )
    }

    @Test
    fun `start launches a coroutine`() = runTest(StandardTestDispatcher()) {
        val scope = TestScope(StandardTestDispatcher())
        val scheduler = DemoCleanupScheduler(service, scope, 24)
        val job = scheduler.start()
        assert(job.isActive)
        scheduler.stop()
    }

    @Test
    fun `stop cancels the job`() = runTest(StandardTestDispatcher()) {
        val scope = TestScope(StandardTestDispatcher())
        val scheduler = DemoCleanupScheduler(service, scope, 24)
        val job = scheduler.start()
        scheduler.stop()
        assert(job.isCancelled)
    }

    @Test
    fun `can start and stop multiple times`() = runTest(StandardTestDispatcher()) {
        val scope = TestScope(StandardTestDispatcher())
        val scheduler = DemoCleanupScheduler(service, scope, 24)
        scheduler.start()
        scheduler.stop()
        scheduler.start()
        scheduler.stop()
        scheduler.start()
        scheduler.stop()
        assert(true)
    }
}
