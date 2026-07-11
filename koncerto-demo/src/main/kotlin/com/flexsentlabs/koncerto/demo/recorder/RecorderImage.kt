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
 */
class RecorderImage {

    fun imageExists(tag: String = IMAGE_TAG): Boolean {
        return try {
            val pb = ProcessBuilder("docker", "images", "-q", tag).redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(10, TimeUnit.SECONDS)
            output.isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pulls [tag] if it isn't already present locally. The first pull is a one-time cost — every
     * subsequent recording reuses the cached image. Returns the tag on success.
     */
    fun ensureAvailable(tag: String = IMAGE_TAG, pullTimeoutSec: Long = 900): Result<String> {
        if (imageExists(tag)) return Result.success(tag)
        return try {
            val pb = ProcessBuilder("docker", "pull", tag).redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(pullTimeoutSec, TimeUnit.SECONDS) && p.exitValue() == 0
            if (ok) Result.success(tag) else Result.failure(RuntimeException("recorder image pull failed:\n$output"))
        } catch (e: Exception) {
            Result.failure(RuntimeException("recorder image pull error: ${e.message}"))
        }
    }

    companion object {
        // The lean recorder image published by .github/workflows/recorder-image.yml. Pulling a
        // prebaked ghcr image (not building locally, not pulling the ~2GB upstream Playwright
        // image) is what keeps recording reliable on constrained local Docker VMs.
        const val IMAGE_TAG = "ghcr.io/ajdevy/koncerto-recorder:latest"
    }
}
