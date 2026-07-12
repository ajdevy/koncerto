package com.flexsentlabs.koncerto.demo.recorder

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class RecorderImageTest {

    // A fake docker runner: records the commands it was asked to run and replays queued outcomes.
    private class FakeDocker(private vararg val outcomes: RecorderImage.DockerRun?) {
        val commands = mutableListOf<List<String>>()
        private var i = 0
        val run: (List<String>, Long) -> RecorderImage.DockerRun? = { command, _ ->
            commands += command
            outcomes.getOrNull(i++).also { }
        }
    }

    @Test
    fun `IMAGE_TAG points at the published ghcr recorder image`() {
        assertThat(RecorderImage.IMAGE_TAG).contains("ghcr.io")
        assertThat(RecorderImage.IMAGE_TAG).contains("koncerto-recorder")
    }

    @Test
    fun `imageExists is true when docker prints an image id`() {
        val docker = FakeDocker(RecorderImage.DockerRun("sha256:abc123\n", 0))
        assertThat(RecorderImage(docker.run).imageExists("some:tag")).isTrue()
        assertThat(docker.commands.single()).isEqualTo(listOf("docker", "images", "-q", "some:tag"))
    }

    @Test
    fun `imageExists is false when docker prints nothing`() {
        val docker = FakeDocker(RecorderImage.DockerRun("   \n", 0))
        assertThat(RecorderImage(docker.run).imageExists("missing:tag")).isFalse()
    }

    @Test
    fun `imageExists is false when docker cannot be run`() {
        val docker = FakeDocker(null)
        assertThat(RecorderImage(docker.run).imageExists("x:tag")).isFalse()
    }

    @Test
    fun `ensureAvailable short-circuits without pulling when the image is present`() {
        val docker = FakeDocker(RecorderImage.DockerRun("sha256:present\n", 0))
        val result = RecorderImage(docker.run).ensureAvailable("present:tag")
        assertThat(result.isSuccess).isTrue()
        // Only the `images -q` probe ran; no pull.
        assertThat(docker.commands.single()[1]).isEqualTo("images")
    }

    @Test
    fun `ensureAvailable pulls and succeeds when the image is absent`() {
        val docker = FakeDocker(
            RecorderImage.DockerRun("", 0),          // images -q → empty → not present
            RecorderImage.DockerRun("Pulled\n", 0)   // pull → success
        )
        val result = RecorderImage(docker.run).ensureAvailable("absent:tag")
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("absent:tag")
        assertThat(docker.commands[1]).isEqualTo(listOf("docker", "pull", "absent:tag"))
    }

    @Test
    fun `ensureAvailable fails when the pull exits non-zero`() {
        val docker = FakeDocker(
            RecorderImage.DockerRun("", 0),
            RecorderImage.DockerRun("manifest unknown", 1)
        )
        val result = RecorderImage(docker.run).ensureAvailable("bad:tag")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("pull failed")
    }

    @Test
    fun `ensureAvailable fails when docker cannot be run at all`() {
        val docker = FakeDocker(
            RecorderImage.DockerRun("", 0),
            null
        )
        val result = RecorderImage(docker.run).ensureAvailable("x:tag")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message!!).contains("pull error")
    }
}
