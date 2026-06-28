package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ExistingDockerPRDetectorTest {

    private val ghApi = mockk<GitHubPRQuery>()
    private val detector = ExistingDockerPRDetector(ghApi)

    @Test
    fun `findExisting returns null when no open PRs`() = runTest {
        coEvery { ghApi.listOpenPRs("owner/repo") } returns emptyList()

        assertThat(detector.findExisting("owner/repo", "main")).isNull()
    }

    @Test
    fun `findExisting returns PR with docker label`() = runTest {
        val labeled = PRInfo(1, "Docker setup", "feat/docker", "main", listOf("docker-setup"), true)
        val other = PRInfo(2, "Feature", "feat/other", "main", emptyList(), true)
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(other, labeled)

        assertThat(detector.findExisting("owner/repo", "main")).isEqualTo(labeled)
    }

    @Test
    fun `findExisting matches modified docker-compose path`() = runTest {
        val pr = PRInfo(3, "Infra", "feat/infra", "main", emptyList(), true)
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(pr)
        coEvery { ghApi.getModifiedFiles(3, "owner/repo") } returns listOf("docker-compose.yml")

        assertThat(detector.findExisting("owner/repo", "main")).isEqualTo(pr)
    }

    @Test
    fun `findExisting ignores PRs on different base branch`() = runTest {
        val pr = PRInfo(4, "Docker", "feat/docker", "develop", listOf("docker-setup"), true)
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(pr)

        assertThat(detector.findExisting("owner/repo", "main")).isNull()
    }

    @Test
    fun `findExisting matches Dockerfile path`() = runTest {
        val pr = PRInfo(5, "Container", "feat/container", "main", emptyList(), true)
        coEvery { ghApi.listOpenPRs("owner/repo") } returns listOf(pr)
        coEvery { ghApi.getModifiedFiles(5, "owner/repo") } returns listOf("Dockerfile")

        assertThat(detector.findExisting("owner/repo", "main")).isEqualTo(pr)
    }
}
