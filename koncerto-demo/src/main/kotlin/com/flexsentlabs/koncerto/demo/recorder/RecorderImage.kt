package com.flexsentlabs.koncerto.demo.recorder

import java.util.concurrent.TimeUnit

/**
 * Ensures the demo recorder's container image is available locally. The recorder runs on the
 * official Playwright image, which ships node, the playwright package, chromium (plus all its
 * system dependencies), and xvfb prebaked — so nothing is installed at recorder build time.
 *
 * This deliberately avoids a custom `docker build`: fetching package indexes (apk/apt) from
 * inside a build proved unreliable in practice (TLS/connection failures reaching the Alpine and
 * Debian mirrors), whereas pulling a prebaked image only needs registry access to the image itself.
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
     * Pulls [tag] if it isn't already present locally. The Playwright image is large (~2GB), so
     * the first pull is slow, but it's a one-time cost — every subsequent recording reuses the
     * cached image. Returns the tag on success.
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
        // The official Playwright image: node + playwright + chromium + browser deps + xvfb,
        // all prebaked. Pinned to a specific version so recordings are reproducible.
        const val IMAGE_TAG = "mcr.microsoft.com/playwright:v1.55.0-jammy"
    }
}
