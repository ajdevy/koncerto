package com.flexsentlabs.koncerto.deploy

import com.flexsentlabs.koncerto.logging.StructuredLogger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrphanedContainerCleanupSchedulerTest {

    @Test
    fun `start runs initial cleanup and schedules periodic cleanup`() = runTest {
        val deployer = mockk<TargetProjectDeployer>()
        coEvery { deployer.cleanupOrphans() } returns Unit
        val scheduler = OrphanedContainerCleanupScheduler(
            deployer = deployer,
            scope = this as CoroutineScope,
            logger = StructuredLogger(emptyList()),
            intervalMinutes = 1
        )

        val job = scheduler.start()
        advanceTimeBy(61_000)
        scheduler.stop()
        job.cancel()
    }

    @Test
    fun `stop cancels scheduled job`() = runTest {
        val deployer = mockk<TargetProjectDeployer>()
        coEvery { deployer.cleanupOrphans() } returns Unit
        val scheduler = OrphanedContainerCleanupScheduler(
            deployer = deployer,
            scope = this as CoroutineScope,
            logger = StructuredLogger(emptyList()),
            intervalMinutes = 5
        )

        scheduler.start()
        scheduler.stop()
    }

    @Test
    fun `start handles cleanup errors gracefully`() = runTest {
        val deployer = mockk<TargetProjectDeployer>()
        coEvery { deployer.cleanupOrphans() } throws RuntimeException("docker unavailable")
        val scheduler = OrphanedContainerCleanupScheduler(
            deployer = deployer,
            scope = this as CoroutineScope,
            logger = StructuredLogger(emptyList()),
            intervalMinutes = 1
        )

        scheduler.start().cancel()
    }
}
