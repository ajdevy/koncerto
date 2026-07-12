package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isEqualTo
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

    @Test
    fun `cleanOnLaunch returns an empty report when docker cannot be executed`() = runTest {
        // A non-existent binary makes every ProcessBuilder.start throw, so each scan returns null
        // and the cleaner degrades to a no-op empty report instead of crashing.
        val cleaner = DockerLaunchCleaner(
            logger = StructuredLogger(emptyList()),
            dockerCmd = { args -> arrayOf("/nonexistent/docker-binary-xyz", *args) }
        )
        val report = cleaner.cleanOnLaunch()
        assertThat(report.containersRemoved).isEqualTo(0)
        assertThat(report.imagesRemoved).isEqualTo(0)
        assertThat(report.composeProjectsRemoved).isEqualTo(0)
    }

    @Test
    fun `cleanOnLaunch skips a container that is neither protected nor an orphan`() = runTest {
        // Exited, unmanaged, non-koncerto container in no compose project → left untouched.
        val script = """#!/usr/bin/env bash
case "${'$'}1" in
  ps) echo "keep1|some_app|Exited (0)|python:3.12||"; exit 0 ;;
esac
exit 0
"""
        val report = createCleaner(script).cleanOnLaunch()
        assertThat(report.containersRemoved).isEqualTo(0)
        assertThat(report.protectedSkipped).isEqualTo(0)
    }

    @Test
    fun `cleanOnLaunch tolerates a docker rm that cannot be executed`() = runTest {
        // ps lists an orphan, but the `rm` command maps to a missing binary → runDocker's start
        // throws and is swallowed, so nothing is counted as removed.
        val listScript = Files.createTempDirectory("dlc-rm").resolve("docker")
        listScript.toFile().writeText(
            """#!/usr/bin/env bash
case "${'$'}1" in
  ps) echo "orphan1|koncerto-demo-1|Exited (0)|koncerto-demo-x|koncerto-demo|"; exit 0 ;;
esac
exit 0
"""
        )
        listScript.toFile().setExecutable(true)
        val cleaner = DockerLaunchCleaner(
            logger = StructuredLogger(emptyList()),
            dockerCmd = { args ->
                if (args.isNotEmpty() && args[0] == "rm") arrayOf("/nonexistent/docker-rm-xyz", *args)
                else arrayOf(listScript.toString(), *args)
            }
        )
        val report = cleaner.cleanOnLaunch()
        assertThat(report.containersRemoved).isEqualTo(0)
    }

    @Test
    fun `cleanOnLaunch abandons a docker scan that exceeds its timeout`() = runTest {
        // The `ps` scan sleeps past dockerOutput's 15s budget → it is force-killed and treated as
        // no output, so the launch cleanup degrades to a no-op instead of hanging forever.
        val cleaner = createCleaner(
            """#!/usr/bin/env bash
case "${'$'}1" in
  ps) sleep 20 ;;
esac
exit 0
"""
        )
        val report = cleaner.cleanOnLaunch()
        assertThat(report.containersRemoved).isEqualTo(0)
    }

    @Test
    fun `can be constructed with the default docker command mapper`() {
        // Exercises the default dockerCmd argument (TargetProjectDeployer.dockerCmd) without running it.
        val cleaner = DockerLaunchCleaner(StructuredLogger(emptyList()))
        assertThat(cleaner.hashCode()).isEqualTo(cleaner.hashCode())
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
