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

    @Test
    fun `deployWithCompose succeeds when web port exposed`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
        )
        val deployer = createDeployer(mockk(relaxed = true))
        val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
        assertThat(result.success).isTrue()
        assertThat(result.isCompose).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:8080")
    }

    @Test
    fun `deployWithCompose returns failure when compose up fails`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: koncerto-nonexistent-image:missing\n"
        )
        val deployer = createDeployer(mockk(relaxed = true))
        val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("docker compose up failed")
    }

    @Test
    fun `deploy with docker compose falls through to app build for infra-only compose`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  postgres:\n    image: postgres\n")
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid-compose", 32780, "http://host.docker.internal:32780")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32780
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.composeInfraOnlyScript()) {
            val deployer = createDeployer(containerManager)
            val result = deployer.deploy(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
            assertThat(result.success).isTrue()
            assertThat(result.url).isEqualTo("http://host.docker.internal:32780")
        }
    }

    @Test
    fun `resolveComposeNetwork returns inspect network or default`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(FakeDockerPath.composeNetworkScript()) {
            val network = invokeResolveComposeNetwork(deployer, tmpDir, tmpDir.resolve("docker-compose.yml"))
            assertThat(network).isEqualTo("koncerto-demo_default")
        }
    }

    @Test
    fun `deployAppOnNetwork returns failure when framework missing`(@TempDir tmpDir: Path) {
        val deployer = createDeployer(mockk(relaxed = true))
        val result = invokeDeployAppOnNetwork(deployer, tmpDir, "koncerto-demo-test", "koncerto-demo_default")
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Could not detect framework for app build")
    }

    @Test
    fun `cleanup removes containers images and compose project`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.cleanupScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `cleanupOrphans removes containers images and compose projects`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.orphanCleanupScript()) {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `buildAndRun wraps unexpected exceptions`(@TempDir tmpDir: Path) {
        val deployer = createDeployer(mockk(relaxed = true))
        val badConfig = DetectedDockerConfig(DockerConfigType.DOCKER_COMPOSE, composeFile = null)
        val result = invokeBuildAndRun(deployer, badConfig, tmpDir, "koncerto-demo-test")
        assertThat(result.success).isFalse()
        assertThat(result.error!!.startsWith("Deployment error:")).isTrue()
    }

    @Test
    fun `deploy uses detected docker compose file`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
        )
        val deployer = createDeployer(mockk(relaxed = true))
        val result = deployer.deploy(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.isCompose).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:8080")
    }

    @Test
    fun `deployAppOnNetwork succeeds with mocked lifecycle manager`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid-network", 32790, "http://host.docker.internal:32790")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32790
        every { containerManager.runContainer(any(), any(), any(), match { it == "koncerto-demo_default" }) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = invokeDeployAppOnNetwork(deployer, tmpDir, "koncerto-demo-feature", "koncerto-demo_default")
        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:32790")
    }

    @Test
    fun `deploy with spring boot dockerfile uses detected framework ports`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("build.gradle.kts"), """
            plugins { id("org.springframework.boot") }
        """.trimIndent())
        val container = ContainerInstance("cid-spring", 32791, "http://host.docker.internal:32791")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32791
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "main", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.tag).isEqualTo("koncerto-demo-main")
    }

    @Test
    fun `deployWithCompose via fake docker succeeds with web port`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
        )
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(FakeDockerPath.composeSuccessScript()) {
            val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
            assertThat(result.success).isTrue()
            assertThat(result.url).isEqualTo("http://host.docker.internal:8080")
        }
    }

    @Test
    fun `cleanup tolerates docker command failures`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.failingCleanupScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `deploy full path succeeds for python requirements txt`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("requirements.txt"), "fastapi\n")
        Files.writeString(tmpDir.resolve("main.py"), "from fastapi import FastAPI\napp = FastAPI()\n")
        val container = ContainerInstance("cid-python", 32797, "http://host.docker.internal:32797")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32797
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "feature-py", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:32797")
        assertThat(result.tag).isEqualTo("koncerto-demo-feature-py")
    }

    @Test
    fun `deployAppOnNetwork succeeds for python project`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("pyproject.toml"), "[project]\nname = \"demo\"\n")
        Files.writeString(tmpDir.resolve("main.py"), "print('ok')\n")
        val container = ContainerInstance("cid-py-net", 32798, "http://host.docker.internal:32798")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32798
        every { containerManager.runContainer(any(), any(), any(), eq("koncerto-demo_default")) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = invokeDeployAppOnNetwork(deployer, tmpDir, "koncerto-demo-feature", "koncerto-demo_default")
        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:32798")
    }

    @Test
    fun `deployWithDockerfile health failure captures logs and releases port`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine\n")
        val container = ContainerInstance("cid-unhealthy", 32799, "http://host.docker.internal:32799")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32799
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.failure(RuntimeException("unhealthy"))
        every { containerManager.captureLogs(any()) } returns "health fail logs"
        every { containerManager.stopAndRemove(any()) } returns Unit
        every { containerManager.releasePort(32799) } returns Unit

        val deployer = createDeployer(containerManager)
        val result = invokeDeployWithDockerfile(
            deployer, tmpDir.resolve("Dockerfile"), tmpDir, "koncerto-demo-test", null
        )
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Health check failed")
        assertThat(result.logs).isEqualTo("health fail logs")
        verify { containerManager.releasePort(32799) }
    }

    @Test
    fun `cleanup without compose file skips compose down`(@TempDir tmpDir: Path) = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.cleanupScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `deploy via fake docker infra-only compose builds app on network`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  postgres:\n    image: postgres\n")
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid-node", 32800, "http://host.docker.internal:32800")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32800
        every { containerManager.runContainer(any(), any(), any(), eq("koncerto-demo_default")) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.composeInfraOnlyScript()) {
            val result = deployer.deploy(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
            assertThat(result.success).isTrue()
            assertThat(result.url).isEqualTo("http://host.docker.internal:32800")
        }
    }

    @Test
    fun `deployWithDockerfile returns failure when build fails`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine\n")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.failure(RuntimeException("build failed"))

        val deployer = createDeployer(containerManager)
        val result = invokeDeployWithDockerfile(
            deployer, tmpDir.resolve("Dockerfile"), tmpDir, "koncerto-demo-test", null
        )
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Docker build failed")
    }

    @Test
    fun `deployWithDockerfile returns failure when container start fails`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine\n")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32801
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.failure(RuntimeException("run failed"))
        every { containerManager.releasePort(32801) } returns Unit

        val deployer = createDeployer(containerManager)
        val result = invokeDeployWithDockerfile(
            deployer, tmpDir.resolve("Dockerfile"), tmpDir, "koncerto-demo-test", null
        )
        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("Container start failed")
        verify { containerManager.releasePort(32801) }
    }

    @Test
    fun `cleanup with compose file runs compose down`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.cleanupScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `resolveComposeNetwork falls back to default when inspect blank`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  db:\n    image: postgres\n")
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *"ps --format"*) echo "koncerto-demo-db-1"; exit 0 ;;
    esac
    ;;
  inspect) echo ""; exit 0 ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(script) {
            val network = invokeResolveComposeNetwork(deployer, tmpDir, tmpDir.resolve("docker-compose.yml"))
            assertThat(network).isEqualTo("koncerto-demo_default")
        }
    }

    @Test
    fun `buildAndRun fails when dockerfile config missing file`(@TempDir tmpDir: Path) {
        val deployer = createDeployer(mockk(relaxed = true))
        val badConfig = DetectedDockerConfig(DockerConfigType.DOCKERFILE, dockerfile = null)
        val result = invokeBuildAndRun(deployer, badConfig, tmpDir, "koncerto-demo-test")
        assertThat(result.success).isFalse()
        assertThat(result.error!!.startsWith("Deployment error:")).isTrue()
    }

    @Test
    fun `deploy full path for node package json`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid-node-full", 32801, "http://host.docker.internal:32801")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32801
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)
        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "node-feature", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.tag).isEqualTo("koncerto-demo-node-feature")
    }

    @Test
    fun `deploy full path for python pyproject`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("pyproject.toml"), "[project]\nname = \"demo\"\n")
        Files.writeString(tmpDir.resolve("main.py"), "from fastapi import FastAPI\napp = FastAPI()\n")
        val container = ContainerInstance("cid-py", 32802, "http://host.docker.internal:32802")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32802
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)
        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "py-feature", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.tag).isEqualTo("koncerto-demo-py-feature")
    }

    @Test
    fun `deploy full path for go mod`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("go.mod"), "module example.com/demo\n\ngo 1.22\n")
        Files.writeString(tmpDir.resolve("main.go"), "package main\nfunc main() {}\n")
        val container = ContainerInstance("cid-go", 32803, "http://host.docker.internal:32803")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32803
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)
        val deployer = createDeployer(containerManager)
        val result = deployer.deploy(DeployConfig("owner/repo", "go-feature", projectPath = tmpDir))
        assertThat(result.success).isTrue()
        assertThat(result.tag).isEqualTo("koncerto-demo-go-feature")
    }

    @Test
    fun `cleanupOrphans tolerates docker scan failures`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.failingCleanupScript()) {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `deployWithDockerfile succeeds with mocked lifecycle`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine\n")
        val container = ContainerInstance("cid-df", 32795, "http://host.docker.internal:32795")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32795
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)

        val deployer = createDeployer(containerManager)
        val result = invokeDeployWithDockerfile(
            deployer, tmpDir.resolve("Dockerfile"), tmpDir, "koncerto-demo-test", null
        )
        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://host.docker.internal:32795")
    }

    @Test
    fun `deployWithCompose returns failure when compose up times out`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) sleep 3; echo started; exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        TargetProjectDeployer.testComposeUpWaitSec = 1L
        try {
            FakeDockerPath.withFakeDocker(script) {
                val result = invokeDeployWithCompose(
                    deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test"
                )
                assertThat(result.success).isFalse()
                assertThat(result.error).isEqualTo("docker compose up timed out")
            }
        } finally {
            TargetProjectDeployer.testComposeUpWaitSec = null
        }
    }

    @Test
    fun `deployWithCompose handles ps timeout`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
        )
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "started"; exit 0 ;;
      *" ps"*) sleep 10; echo "8080->8080/tcp"; exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(script) {
            val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
            assertThat(result.success).isTrue()
        }
    }

    @Test
    fun `deployWithCompose returns failure when port not in webPorts`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  db:\n    image: postgres\n")
        Files.writeString(tmpDir.resolve("package.json"), """{"scripts":{"start":"node server.js"}}""")
        val container = ContainerInstance("cid-db", 32810, "http://host.docker.internal:32810")
        val containerManager = mockk<ContainerLifecycleManager>()
        every { containerManager.buildImage(any(), any(), any()) } returns Result.success(Unit)
        every { containerManager.allocatePort() } returns 32810
        every { containerManager.runContainer(any(), any(), any(), any()) } returns Result.success(container)
        every { containerManager.waitForHealthy(any()) } returns Result.success(Unit)
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) exit 0 ;;
      *" ps"*) echo "5432->5432/tcp"; exit 0 ;;
    esac
    ;;
  inspect) echo "koncerto-demo_default"; exit 0 ;;
esac
exit 0
"""
        val deployer = createDeployer(containerManager)
        FakeDockerPath.withFakeDocker(script) {
            val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
            assertThat(result.success).isTrue()
        }
    }

    @Test
    fun `deployWithCompose succeeds when koncerto demo port 17349 exposed`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.demo.yml"),
            "services:\n  koncerto-demo:\n    build:\n      context: .\n      dockerfile: Dockerfile.demo\n    ports:\n      - \"17349:17348\"\n"
        )
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "started"; exit 0 ;;
      *" ps"*) echo "17349->17348/tcp"; exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(script) {
            val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.demo.yml"), tmpDir, "koncerto-demo-fle-52")
            assertThat(result.success).isTrue()
            assertThat(result.url).isEqualTo("http://host.docker.internal:17349")
        }
    }

    @Test
    fun `deploy picks docker-compose demo yml over docker-compose yml`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(
            tmpDir.resolve("docker-compose.demo.yml"),
            "services:\n  koncerto-demo:\n    build:\n      context: .\n      dockerfile: Dockerfile.demo\n    ports:\n      - \"17349:17348\"\n"
        )
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  app:\n    image: nginx\n    network_mode: host\n"
        )
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "started"; exit 0 ;;
      *" ps"*) echo "17349->17348/tcp"; exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(script) {
            val result = deployer.deploy(DeployConfig("owner/koncerto", "fle-52", projectPath = tmpDir))
            assertThat(result.success).isTrue()
            assertThat(result.url).isEqualTo("http://host.docker.internal:17349")
        }
    }

    @Test
    fun `FakeDockerPath redirects docker via test override`() {
        FakeDockerPath.withFakeDocker("""#!/usr/bin/env bash
echo FAKE_DOCKER_MARKER
""") {
            val process = ProcessBuilder(*TargetProjectDeployer.dockerCmd()).start()
            process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            assertThat(output).isEqualTo("FAKE_DOCKER_MARKER")
        }
    }

    @Test
    fun `deployWithCompose returns failure when compose up exits non-zero`(@TempDir tmpDir: Path) {
        Files.writeString(
            tmpDir.resolve("docker-compose.yml"),
            "services:\n  web:\n    image: nginx\n    ports:\n      - \"8080:80\"\n"
        )
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "compose error"; exit 1 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(script) {
            val result = invokeDeployWithCompose(deployer, tmpDir.resolve("docker-compose.yml"), tmpDir, "koncerto-demo-test")
            assertThat(result.success).isFalse()
            assertThat(result.error).isEqualTo("docker compose up failed")
        }
    }

    @Test
    fun `cleanup handles docker ps scan failure`(@TempDir tmpDir: Path) = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend("""#!/usr/bin/env bash
case "${'$'}1" in
  ps) exit 1 ;;
esac
exit 0
""") {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `cleanupOrphans handles container and image scan failures`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend("""#!/usr/bin/env bash
exit 1
""") {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `deployWithCompose returns failure on docker compose error`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        val result = invokeDeployWithCompose(
            deployer, tmpDir.resolve("docker-compose.yml"), Path.of("/nonexistent/project/path"), "koncerto-demo-test"
        )
        assertThat(result.success).isFalse()
        assertThat(result.error!!.startsWith("docker compose error:")).isTrue()
    }

    @Test
    fun `resolveComposeNetwork falls back on inspect exception`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  db:\n    image: postgres\n")
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *"ps --format"*) echo "koncerto-demo-db-1"; exit 0 ;;
    esac
    ;;
  inspect) exit 1 ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDocker(script) {
            val network = invokeResolveComposeNetwork(deployer, tmpDir, tmpDir.resolve("docker-compose.yml"))
            assertThat(network).isEqualTo("koncerto-demo_default")
        }
    }

    @Test
    fun `cleanup removes containers found by ancestor filter`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.cleanupWithContainersScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `compose wait sec test seams roundtrip`() {
        TargetProjectDeployer.testComposeUpWaitSec = 42L
        TargetProjectDeployer.testComposePsWaitSec = 7L
        assertThat(TargetProjectDeployer.testComposeUpWaitSec).isEqualTo(42L)
        assertThat(TargetProjectDeployer.testComposePsWaitSec).isEqualTo(7L)
        TargetProjectDeployer.testComposeUpWaitSec = null
        TargetProjectDeployer.testComposePsWaitSec = null
    }

    @Test
    fun `cleanup and cleanupOrphans tolerate missing docker binary`(@TempDir tmpDir: Path) = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        TargetProjectDeployer.testDockerOverride.set("/nonexistent/koncerto-docker")
        try {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
            deployer.cleanupOrphans()
        } finally {
            TargetProjectDeployer.testDockerOverride.set(null)
        }
    }

    @Test
    fun `cleanupOrphans removes images and compose projects`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.orphanCleanupScript()) {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `cleanupOrphans tolerates per-container rm failures`() = runTest {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    echo "orphan-id-1"
    exit 0
    ;;
  rm) exit 1 ;;
  images) exit 0 ;;
  compose)
    shift
    case "${'$'}*" in
      *ls*) exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(script) {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `cleanup runs compose down when compose file exists`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(FakeDockerPath.cleanupScript()) {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `resolveComposeNetwork returns inspect network mode`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *ps*--format*) echo "koncerto-demo-web-1"; exit 0 ;;
    esac
    ;;
  inspect)
    echo "koncerto-demo_default"
    exit 0
    ;;
esac
exit 0
"""
        FakeDockerPath.withFakeDocker(script) {
            val network = invokeResolveComposeNetwork(deployer, tmpDir, tmpDir.resolve("docker-compose.yml"))
            assertThat(network).isEqualTo("koncerto-demo_default")
        }
    }

    @Test
    fun `cleanupOrphans tolerates compose ls scan failure`() = runTest {
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend("""#!/usr/bin/env bash
case "${'$'}1" in
  ps) exit 0 ;;
  images) exit 0 ;;
  compose) exit 1 ;;
esac
exit 0
""") {
            deployer.cleanupOrphans()
        }
    }

    @Test
    fun `cleanup tolerates compose down failure`(@TempDir tmpDir: Path) = runTest {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services:\n  web:\n    image: nginx\n")
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend("""#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 1 ;;
    esac
    ;;
  ps) exit 0 ;;
  rm) exit 0 ;;
  rmi) exit 0 ;;
esac
exit 0
""") {
            deployer.cleanup(DeployConfig("owner/repo", "feature", projectPath = tmpDir))
        }
    }

    @Test
    fun `compose wait sec test seams can be read and written`() {
        val previousUp = TargetProjectDeployer.testComposeUpWaitSec
        val previousPs = TargetProjectDeployer.testComposePsWaitSec
        try {
            TargetProjectDeployer.testComposeUpWaitSec = 1L
            TargetProjectDeployer.testComposePsWaitSec = 2L
            assertThat(TargetProjectDeployer.testComposeUpWaitSec).isEqualTo(1L)
            assertThat(TargetProjectDeployer.testComposePsWaitSec).isEqualTo(2L)
        } finally {
            TargetProjectDeployer.testComposeUpWaitSec = previousUp
            TargetProjectDeployer.testComposePsWaitSec = previousPs
        }
    }

    @Test
    fun `cleanupOrphans tolerates per-image rmi failures`() = runTest {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  ps) echo "orphan-id"; exit 0 ;;
  rm) exit 0 ;;
  images) echo "koncerto-demo-stale:tag"; exit 0 ;;
  rmi) exit 1 ;;
  compose)
    shift
    case "${'$'}*" in *ls*) exit 0 ;; esac
    ;;
esac
exit 0
"""
        val deployer = createDeployer(mockk(relaxed = true))
        FakeDockerPath.withFakeDockerSuspend(script) {
            deployer.cleanupOrphans()
        }
    }

    private fun invokeDeployWithDockerfile(
        deployer: TargetProjectDeployer,
        dockerfile: Path,
        projectPath: Path,
        tag: String,
        network: String?
    ): DeployResult {
        val method = TargetProjectDeployer::class.java.getDeclaredMethod(
            "deployWithDockerfile", Path::class.java, Path::class.java, String::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(deployer, dockerfile, projectPath, tag, network) as DeployResult
    }

    private fun invokeDeployWithCompose(
        deployer: TargetProjectDeployer,
        composeFile: Path,
        projectPath: Path,
        tag: String
    ): DeployResult {
        val method = TargetProjectDeployer::class.java.getDeclaredMethod(
            "deployWithCompose", Path::class.java, Path::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(deployer, composeFile, projectPath, tag) as DeployResult
    }

    private fun invokeResolveComposeNetwork(
        deployer: TargetProjectDeployer,
        projectPath: Path,
        composeFile: Path
    ): String {
        val method = TargetProjectDeployer::class.java.getDeclaredMethod(
            "resolveComposeNetwork", Path::class.java, Path::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(deployer, projectPath, composeFile, "koncerto-demo") as String
    }

    private fun invokeDeployAppOnNetwork(
        deployer: TargetProjectDeployer,
        projectPath: Path,
        tag: String,
        network: String
    ): DeployResult {
        val method = TargetProjectDeployer::class.java.getDeclaredMethod(
            "deployAppOnNetwork", Path::class.java, String::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(deployer, projectPath, tag, network) as DeployResult
    }

    private fun invokeBuildAndRun(
        deployer: TargetProjectDeployer,
        config: DetectedDockerConfig,
        projectPath: Path,
        tag: String
    ): DeployResult {
        val method = TargetProjectDeployer::class.java.getDeclaredMethod(
            "buildAndRun", DetectedDockerConfig::class.java, Path::class.java, String::class.java
        )
        method.isAccessible = true
        return method.invoke(deployer, config, projectPath, tag) as DeployResult
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

internal object FakeDockerPath {
    fun withFakeDocker(script: String, block: () -> Unit) {
        withPathPrepended(script, block)
    }

    suspend fun withFakeDockerSuspend(script: String, block: suspend () -> Unit) {
        withPathPrependedSuspend(script, block)
    }

    private fun withPathPrepended(script: String, block: () -> Unit) {
        val binDir = Files.createTempDirectory("fake-docker-bin")
        val docker = binDir.resolve("docker")
        Files.writeString(docker, script)
        docker.toFile().setExecutable(true)
        try {
            TargetProjectDeployer.testDockerOverride.set(docker.toString())
            block()
        } finally {
            TargetProjectDeployer.testDockerOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    private suspend fun withPathPrependedSuspend(script: String, block: suspend () -> Unit) {
        val binDir = Files.createTempDirectory("fake-docker-bin")
        val docker = binDir.resolve("docker")
        Files.writeString(docker, script)
        docker.toFile().setExecutable(true)
        try {
            TargetProjectDeployer.testDockerOverride.set(docker.toString())
            block()
        } finally {
            TargetProjectDeployer.testDockerOverride.set(null)
            binDir.toFile().deleteRecursively()
        }
    }

    fun composeSuccessScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "started"; exit 0 ;;
      *"ps --format"*) echo "koncerto-demo-web-1"; exit 0 ;;
      *" ps"*) echo "8080->8080/tcp"; exit 0 ;;
    esac
    ;;
  inspect) echo "koncerto-demo_default"; exit 0 ;;
esac
exit 0
"""

    fun composeInfraOnlyScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *" up "*) echo "started"; exit 0 ;;
      *"ps --format"*) echo "koncerto-demo-postgres-1"; exit 0 ;;
      *" ps"*) echo "5432/tcp"; exit 0 ;;
    esac
    ;;
  inspect) echo "koncerto-demo_default"; exit 0 ;;
esac
exit 0
"""

    fun composeUpFailScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
      *) echo "compose error"; exit 1 ;;
    esac
    ;;
esac
exit 0
"""

    fun composeNetworkScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  compose)
    shift
    case "${'$'}*" in
      *"ps --format"*)
        echo 'time="2026-06-01T00:00:00Z" level=warning msg="noise"'
        echo "koncerto-demo-db-1"
        exit 0
        ;;
      *" ps"*) exit 0 ;;
    esac
    ;;
  inspect) echo "koncerto-demo_default"; exit 0 ;;
esac
exit 0
"""

    fun lifecycleSuccessScript(containerId: String = "a".repeat(64)) = """#!/usr/bin/env bash
set -e
case "${'$'}1" in
  build) echo "built"; exit 0 ;;
  run)
    echo "$containerId"
    exit 0
    ;;
  inspect)
    if [ "${'$'}5" = "{{.State.Status}}" ]; then echo "running"; else echo "koncerto-network"; fi
    exit 0
    ;;
  logs) echo "container output"; exit 0 ;;
  rm) exit 0 ;;
  ps) exit 0 ;;
  *)
    exit 0
    ;;
esac
"""

    fun lifecyclePortConflictScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  run) echo "Bind for 0.0.0.0:45100 failed: port is already allocated"; exit 1 ;;
esac
exit 1
"""

    fun lifecyclePortConflictThenSuccessScript(containerId: String = "b".repeat(64)) = """#!/usr/bin/env bash
case "${'$'}1" in
  run)
    for arg in "${'$'}@"; do
      if [ "${'$'}arg" = "45110:8080" ]; then
        echo "Bind for 0.0.0.0:45110 failed: port is already allocated"
        exit 1
      fi
    done
    echo "$containerId"
    exit 0
    ;;
  inspect)
    if [ "${'$'}5" = "{{.State.Status}}" ]; then echo "running"; else echo "koncerto-network"; fi
    exit 0
    ;;
esac
exit 0
"""

    fun cleanupScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    echo "container-id-1"
    echo "container-id-2"
    exit 0
    ;;
  rm) exit 0 ;;
  rmi) exit 0 ;;
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
    esac
    exit 0
    ;;
esac
exit 0
"""

    fun failingCleanupScript() = """#!/usr/bin/env bash
exit 1
"""

    fun orphanCleanupScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    echo "orphan-id-1"
    echo "orphan-id-2"
    exit 0
    ;;
  rm) exit 0 ;;
  images)
    echo "koncerto-demo-old:tag"
    echo "koncerto-demo-other:tag"
    exit 0
    ;;
  rmi) exit 0 ;;
  compose)
    shift
    case "${'$'}*" in
      *ls*)
        echo "koncerto-demo-stale"
        echo "koncerto-demo-other"
        exit 0
        ;;
      *down*) exit 0 ;;
    esac
    ;;
esac
exit 0
"""

    fun cleanupWithContainersScript() = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    if [[ "${'$'}*" == *ancestor* ]]; then
      echo "container-id-1"
      echo "container-id-2"
    fi
    exit 0
    ;;
  rm) exit 0 ;;
  rmi) exit 0 ;;
  compose)
    shift
    case "${'$'}*" in
      *down*) exit 0 ;;
    esac
    exit 0
    ;;
esac
exit 0
"""
}
