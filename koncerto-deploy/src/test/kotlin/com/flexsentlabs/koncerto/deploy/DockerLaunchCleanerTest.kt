package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isGreaterThan
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DockerLaunchCleanerTest {

    @Test
    fun `cleanOnLaunch removes orphan containers and images`() = runTest {
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  ps)
    echo "orphan1|koncerto-demo-1|Exited (0)|koncerto-demo-x|koncerto-demo|koncerto"
    echo "running1|koncerto-fle52-demo|Up 3 hours|ghcr.io/ajdevy/koncerto:latest||"
    exit 0
    ;;
  rm) exit 0 ;;
  images)
    echo "koncerto-demo-stale:latest"
    echo "koncerto-agent:latest"
    exit 0
    ;;
  rmi) exit 0
    ;;
  compose)
    shift
  case "${'$'}*" in
    *ls*) echo "koncerto-demo"; exit 0 ;;
    *down*) exit 0 ;;
  esac
    ;;
  image)
    shift
    case "${'$'}1" in
      prune) exit 0 ;;
    esac
    ;;
esac
exit 0
"""
        val cleaner = createCleaner(script)
        val report = cleaner.cleanOnLaunch()
        assertThat(report.containersRemoved).isGreaterThan(0)
        assertThat(report.imagesRemoved).isGreaterThan(0)
        assertThat(report.composeProjectsRemoved).isGreaterThan(0)
    }

    @Test
    fun `cleanOnLaunch tolerates docker failures`() = runTest {
        val cleaner = createCleaner("""#!/usr/bin/env bash
exit 1
""")
        cleaner.cleanOnLaunch()
    }

    private fun createCleaner(script: String): DockerLaunchCleaner {
        val docker = Files.createTempDirectory("docker-launch-cleaner").resolve("docker")
        docker.toFile().writeText(script)
        docker.toFile().setExecutable(true)
        return DockerLaunchCleaner(
            logger = StructuredLogger(emptyList()),
            dockerCmd = { args -> arrayOf(docker.toString(), *args) }
        )
    }
}
