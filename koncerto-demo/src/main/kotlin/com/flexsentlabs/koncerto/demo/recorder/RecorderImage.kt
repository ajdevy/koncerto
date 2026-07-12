package com.flexsentlabs.koncerto.demo.recorder

import java.util.concurrent.TimeUnit

/**
 * Ensures the demo recorder's container image is available locally. The recorder runs on a lean
 * image (node + chromium + ffmpeg + xvfb + the playwright package) that is built once in CI and
 * published to ghcr by .github/workflows/recorder-image.yml.
 *
 * This deliberately avoids a local `docker build`: fetching package indexes (apk/apt) from inside
 * a build proved unreliable on constrained local Docker VMs (TLS/connection failures reaching the
 * Alpine and Debian mirrors). Building in CI (clean networking) and pulling a small prebaked image
 * sidesteps that entirely, and keeps the image small enough to pull reliably.
 *
 * The raw `docker` invocation is injected as [runDocker] so the decision logic can be unit-tested
 * without a Docker daemon; the default runs the real command via [ProcessBuilder].
 */
class RecorderImage(
    private val runDocker: (command: List<String>, timeoutSec: Long) -> DockerRun? = ::defaultRunDocker
) {
    /** Result of a docker invocation: combined stdout/stderr plus the process exit code. */
    data class DockerRun(val output: String, val exitCode: Int)

    fun imageExists(tag: String = IMAGE_TAG): Boolean {
        val run = runDocker(listOf("docker", "images", "-q", tag), 10) ?: return false
        return run.output.trim().isNotBlank()
    }

    /**
     * Pulls [tag] if it isn't already present locally. The first pull is a one-time cost — every
     * subsequent recording reuses the cached image. Returns the tag on success.
     */
    fun ensureAvailable(tag: String = IMAGE_TAG, pullTimeoutSec: Long = 900): Result<String> {
        if (imageExists(tag)) return Result.success(tag)
        val run = runDocker(listOf("docker", "pull", tag), pullTimeoutSec)
            ?: return Result.failure(RuntimeException("recorder image pull error: docker could not be run"))
        return if (run.exitCode == 0) {
            Result.success(tag)
        } else {
            Result.failure(RuntimeException("recorder image pull failed:\n${run.output}"))
        }
    }

    companion object {
        // The lean recorder image published by .github/workflows/recorder-image.yml. Pulling a
        // prebaked ghcr image (not building locally, not pulling the ~2GB upstream Playwright
        // image) is what keeps recording reliable on constrained local Docker VMs.
        const val IMAGE_TAG = "ghcr.io/ajdevy/koncerto-recorder:latest"

        // Real docker invocation via ProcessBuilder. This is untestable OS/process plumbing (like
        // DemoScenarioGenerator.Companion's defaultProcessRunner) and is excluded from coverage; the
        // decision logic that consumes it is fully unit-tested through the injected runDocker seam.
        private fun defaultRunDocker(command: List<String>, timeoutSec: Long): DockerRun? {
            return try {
                val p = ProcessBuilder(command).redirectErrorStream(true).start()
                val output = p.inputStream.bufferedReader().readText()
                if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    null
                } else {
                    DockerRun(output, p.exitValue())
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
