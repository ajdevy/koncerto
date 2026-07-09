package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RecorderImageTest {

    private val testTag = "koncerto-recorder-test:${System.nanoTime()}"

    @AfterEach
    fun removeTestImage() {
        runCatching {
            ProcessBuilder("docker", "rmi", "-f", testTag).start().waitFor(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `DOCKERFILE_CONTENT installs playwright and chromium`() {
        assertThat(RecorderImage.DOCKERFILE_CONTENT).contains("playwright")
        assertThat(RecorderImage.DOCKERFILE_CONTENT).contains("chromium")
        assertThat(RecorderImage.DOCKERFILE_CONTENT).contains("ffmpeg")
        assertThat(RecorderImage.DOCKERFILE_CONTENT).contains("xvfb")
    }

    @Test
    fun `imageExists returns false for a tag that was never built`() {
        val image = RecorderImage()
        assertThat(image.imageExists("koncerto-recorder-definitely-not-built:xyz")).isFalse()
    }

    @Test
    fun `ensureBuilt builds a fast trivial image and reports it as existing afterward`() {
        val image = RecorderImage()
        val result = image.ensureBuilt(tag = testTag, dockerfileContent = "FROM alpine:3.20\n")

        assertThat(result.isSuccess).isTrue()
        assertThat(image.imageExists(testTag)).isTrue()
    }

    @Test
    fun `ensureBuilt skips the build when the image already exists`() {
        val image = RecorderImage()
        val firstBuild = image.ensureBuilt(tag = testTag, dockerfileContent = "FROM alpine:3.20\n")
        check(firstBuild.isSuccess) { "precondition failed: first build did not succeed: ${firstBuild.exceptionOrNull()}" }

        // A deliberately broken Dockerfile — if ensureBuilt tried to rebuild, this would fail.
        val result = image.ensureBuilt(tag = testTag, dockerfileContent = "NOT A VALID DOCKERFILE")

        assertThat(result.isSuccess).isTrue()
    }
}
