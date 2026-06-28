package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ProjectDeployerTest {

    @Test
    fun `ProjectDeployer can be implemented`() = runTest {
        val deployer = object : ProjectDeployer {
            override suspend fun deploy(config: DeployConfig) =
                DeployResult.success("http://localhost:8080")
            override suspend fun cleanup(config: DeployConfig) {}
        }

        val config = DeployConfig("owner/repo", "main", projectPath = java.nio.file.Path.of("."))
        val result = deployer.deploy(config)

        assertThat(result.success).isEqualTo(true)
        assertThat(result.url).isEqualTo("http://localhost:8080")
        deployer.cleanup(config)
    }
}
