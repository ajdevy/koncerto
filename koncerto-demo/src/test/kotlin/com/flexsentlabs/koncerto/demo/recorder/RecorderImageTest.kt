package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class RecorderImageTest {

    @Test
    fun `IMAGE_TAG points at the playwright base image`() {
        assertThat(RecorderImage.IMAGE_TAG).contains("playwright")
    }

    @Test
    fun `imageExists returns false for a tag that is not present locally`() {
        val image = RecorderImage()
        assertThat(image.imageExists("koncerto-recorder-definitely-not-present:xyz")).isFalse()
    }

    @Test
    fun `ensureAvailable short-circuits when the image is already present`() {
        // Guarantee a tiny image is present, then assert ensureAvailable returns success WITHOUT
        // attempting a pull. The 1-second pull timeout proves the pull path is never reached — a
        // real pull could not finish that fast, so success here can only come from the
        // imageExists short-circuit.
        ProcessBuilder("docker", "pull", "alpine:3.20").start().waitFor(180, TimeUnit.SECONDS)

        val image = RecorderImage()
        val result = image.ensureAvailable(tag = "alpine:3.20", pullTimeoutSec = 1)

        assertThat(result.isSuccess).isTrue()
    }
}
