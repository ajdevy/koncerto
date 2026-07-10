package com.flexsentlabs.koncerto.demo.recorder

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Builds and caches the lean image the demo recorder runs in: node, chromium, ffmpeg, xvfb,
 * and the playwright npm package. Mirrors the equivalent subset of Dockerfile.demo — no JAR,
 * no docker-cli, no git/gh, since this container's only job is running the extracted
 * PLAYWRIGHT_SCRIPT against a URL and producing a video file.
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
     * Builds [tag] from [dockerfileContent] if it doesn't already exist. The [dockerfileContent]
     * parameter defaults to the real recorder image but is overridable so tests can build a
     * trivial, fast image to exercise the build mechanics without paying for the full
     * chromium+playwright install.
     */
    fun ensureBuilt(tag: String = IMAGE_TAG, dockerfileContent: String = DOCKERFILE_CONTENT): Result<String> {
        if (imageExists(tag)) return Result.success(tag)
        return try {
            val contextDir = Files.createTempDirectory("koncerto-recorder-build").toFile()
            try {
                val dockerfile = File(contextDir, "Dockerfile.recorder")
                dockerfile.writeText(dockerfileContent)
                val pb = ProcessBuilder(
                    "docker", "build", "-f", dockerfile.absolutePath, "-t", tag, contextDir.absolutePath
                ).redirectErrorStream(true)
                val p = pb.start()
                val output = p.inputStream.bufferedReader().readText()
                val ok = p.waitFor(600, TimeUnit.SECONDS) && p.exitValue() == 0
                if (ok) Result.success(tag) else Result.failure(RuntimeException("recorder image build failed:\n$output"))
            } finally {
                contextDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Result.failure(RuntimeException("recorder image build error: ${e.message}"))
        }
    }

    companion object {
        const val IMAGE_TAG = "koncerto-recorder:latest"

        val DOCKERFILE_CONTENT = """
            FROM node:20-alpine

            RUN apk add --no-cache \
                bash \
                chromium \
                nss \
                freetype \
                harfbuzz \
                ca-certificates \
                ttf-freefont \
                ffmpeg \
                xvfb

            RUN npm install -g playwright

            ENV PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/usr/bin/chromium-browser
            ENV NODE_PATH=/usr/local/lib/node_modules

            WORKDIR /work
        """.trimIndent()
    }
}
