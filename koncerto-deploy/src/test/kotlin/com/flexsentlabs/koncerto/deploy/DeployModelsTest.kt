package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class DeployModelsTest {

    @Test
    fun `DeployResult success factory`() {
        val result = DeployResult.success("http://localhost:8080", isCompose = true, tag = "koncerto-demo-main")

        assertThat(result.success).isTrue()
        assertThat(result.url).isEqualTo("http://localhost:8080")
        assertThat(result.isCompose).isTrue()
        assertThat(result.tag).isEqualTo("koncerto-demo-main")
        assertThat(result.error).isNull()
    }

    @Test
    fun `DeployResult success factory accepts internalUrl`() {
        val result = DeployResult.success("http://localhost:8080", tag = "koncerto-demo-main", internalUrl = "http://koncerto-demo-123:8080")

        assertThat(result.internalUrl).isEqualTo("http://koncerto-demo-123:8080")
    }

    @Test
    fun `DeployResult internalUrl defaults to null`() {
        val result = DeployResult.success("http://localhost:8080")

        assertThat(result.internalUrl).isNull()
    }

    @Test
    fun `DeployResult failure factory`() {
        val result = DeployResult.failure("build failed", logs = "error output")

        assertThat(result.success).isFalse()
        assertThat(result.error).isEqualTo("build failed")
        assertThat(result.logs).isEqualTo("error output")
        assertThat(result.url).isNull()
    }

    @Test
    fun `DeployResult data class uses default isCompose and tag`() {
        val result = DeployResult(url = "http://localhost:8080", success = true, error = null, logs = null)

        assertThat(result.isCompose).isFalse()
        assertThat(result.tag).isNull()
    }

    @Test
    fun `ContainerInstance holds connection info`() {
        val instance = ContainerInstance("abc123", 32768, "http://host.docker.internal:32768")

        assertThat(instance.containerId).isEqualTo("abc123")
        assertThat(instance.hostPort).isEqualTo(32768)
        assertThat(instance.baseUrl).isEqualTo("http://host.docker.internal:32768")
    }

    @Test
    fun `ContainerInstance internalUrl combines container name and port`() {
        val instance = ContainerInstance(
            containerId = "abc123",
            hostPort = 32768,
            baseUrl = "http://host.docker.internal:32768",
            containerName = "koncerto-demo-1783600000000",
            containerPort = 8080,
            network = "koncerto-demo_default"
        )

        assertThat(instance.internalUrl).isEqualTo("http://koncerto-demo-1783600000000:8080")
        assertThat(instance.network).isEqualTo("koncerto-demo_default")
    }

    @Test
    fun `ContainerInstance defaults new fields for backward compatibility`() {
        val instance = ContainerInstance("abc123", 32768, "http://host.docker.internal:32768")

        assertThat(instance.containerName).isEqualTo("")
        assertThat(instance.containerPort).isEqualTo(0)
        assertThat(instance.network).isEqualTo("")
        assertThat(instance.internalUrl).isEqualTo("http://:0")
    }

    @Test
    fun `DeployConfig stores project path`() {
        val path = Paths.get("/tmp/project")
        val config = DeployConfig("owner/repo", "feature/branch", "main", path)

        assertThat(config.repoFullName).isEqualTo("owner/repo")
        assertThat(config.prBranch).isEqualTo("feature/branch")
        assertThat(config.baseBranch).isEqualTo("main")
        assertThat(config.projectPath).isEqualTo(path)
    }

    @Test
    fun `PRInfo stores pull request metadata`() {
        val pr = PRInfo(42, "Add Docker", "feat/docker", "main", listOf("docker-setup"), true)

        assertThat(pr.number).isEqualTo(42)
        assertThat(pr.title).isEqualTo("Add Docker")
        assertThat(pr.headBranch).isEqualTo("feat/docker")
        assertThat(pr.labels).isEqualTo(listOf("docker-setup"))
        assertThat(pr.checksPassing).isTrue()
    }
}
