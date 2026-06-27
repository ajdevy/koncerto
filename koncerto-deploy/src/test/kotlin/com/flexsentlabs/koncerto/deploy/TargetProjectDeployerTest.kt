package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TargetProjectDeployerTest {

    private val logger = StructuredLogger(emptyList())

    @Test
    fun `deploy returns failure when no framework detected`(@TempDir tmpDir: Path) = runTest {
        val deployer = createDeployer(containerManager = mockk(relaxed = true))

        val result = deployer.deploy(DeployConfig("owner/repo", "feature", projectPath = tmpDir))

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Could not detect project framework type")
    }

    @Test
    fun `deploy succeeds with generated dockerfile`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid123", 32768, "http://host.docker.internal:32768")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32768
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "feature/branch", projectPath = tmpDir))

        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:32768")
        assertThat(result.tag).isEqualTo("koncerto-demo-feature-branch")
    }

    @Test
    fun `deploy returns failure when docker build fails`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("go.mod"), "module example.com/app\ngo 1.22\n")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.failure(RuntimeException("build error"))

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "main", projectPath = tmpDir))

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Docker build failed")
        assertThat(result.logs).isEqualTo("build error")
    }

    @Test
    fun `deploy returns failure when container start fails`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"npm start"}}""")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32769
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.failure(RuntimeException("port in use"))
        every { containerManager.releasePort(32769) } returns Unit

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "main", projectPath = tmpDir))

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Container start failed")
        verify { containerManager.releasePort(32769) }
    }

    @Test
    fun `deploy returns failure when health check fails`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"npm start"}}""")
        val container = ContainerInstance("cid456", 32770, "http://host.docker.internal:32770")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32770
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.failure(RuntimeException("unhealthy"))
        every { containerManager.captureLogs(any()) } returns "container logs"
        every { containerManager.stopAndRemove(any()) } returns Unit
        every { containerManager.releasePort(32770) } returns Unit

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "main", projectPath = tmpDir))

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Health check failed")
        assertThat(result.logs).isEqualTo("container logs")
    }

    @Test
    fun `deploy uses existing dockerfile config`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine\n")
        val container = ContainerInstance("cid789", 32771, "http://host.docker.internal:32771")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32771
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "main", projectPath = tmpDir))

        assertThat(result.success).isTrue()
    }

    @Test
    fun `cleanup runs without throwing`(@TempDir tmpDir: Path) = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        deployer.cleanup(DeployConfig("owner/repo", "main", projectPath = tmpDir))
    }

    @Test
    fun `cleanupOrphans runs without throwing`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        deployer.cleanupOrphans()
    }

    private fun createDeployer(containerManager: ContainerLifecycleManager): TargetProjectDeployer {
        return TargetProjectDeployer(
            configDetector = DockerConfigDetector(),
            frameworkDetector = FrameworkDetector(),
            dockerfileGenerator = DockerfileGenerator(),
            containerManager = containerManager,
            logger = logger
        )
    }
}
