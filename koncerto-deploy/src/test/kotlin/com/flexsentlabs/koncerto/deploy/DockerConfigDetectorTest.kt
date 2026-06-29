package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DockerConfigDetectorTest {

    private val detector = DockerConfigDetector()

    @Test
    fun `detect returns null when no docker config`(@TempDir tmpDir: Path) {
        assertThat(detector.detect(tmpDir)).isNull()
    }

    @Test
    fun `DockerConfigType enum values are stable`() {
        assertThat(DockerConfigType.DOCKERFILE.name).isEqualTo("DOCKERFILE")
        assertThat(DockerConfigType.values().toList()).contains(DockerConfigType.DOCKER_COMPOSE)
    }

    @Test
    fun `detect finds docker-compose yml`(@TempDir tmpDir: Path) {
        val compose = tmpDir.resolve("docker-compose.yml")
        Files.writeString(compose, "services:\n  app:\n    image: nginx\n")

        val result = detector.detect(tmpDir)

        assertThat(result).isNotNull()
        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
        assertThat(result.composeFile).isEqualTo(compose)
    }

    @Test
    fun `detect finds docker-compose yaml extension`(@TempDir tmpDir: Path) {
        val compose = tmpDir.resolve("docker-compose.yaml")
        Files.writeString(compose, "services:\n  app:\n    image: nginx\n")

        val result = detector.detect(tmpDir)

        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
        assertThat(result.composeFile).isEqualTo(compose)
    }

    @Test
    fun `detect prefers compose over dockerfile`(@TempDir tmpDir: Path) {
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services: {}")
        Files.writeString(tmpDir.resolve("Dockerfile"), "FROM alpine")

        val result = detector.detect(tmpDir)

        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
    }

    @Test
    fun `detect finds dockerfile when no compose`(@TempDir tmpDir: Path) {
        val dockerfile = tmpDir.resolve("Dockerfile")
        Files.writeString(dockerfile, "FROM node:20")

        val result = detector.detect(tmpDir)

        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKERFILE)
        assertThat(result.dockerfile).isEqualTo(dockerfile)
    }

    @Test
    fun `detect finds docker-compose prod file`(@TempDir tmpDir: Path) {
        val compose = tmpDir.resolve("docker-compose.prod.yml")
        Files.writeString(compose, "services: {}")

        val result = detector.detect(tmpDir)

        assertThat(result!!.composeFile).isEqualTo(compose)
    }

    @Test
    fun `detect finds docker-compose demo file`(@TempDir tmpDir: Path) {
        val compose = tmpDir.resolve("docker-compose.demo.yml")
        Files.writeString(compose, "services: {}")

        val result = detector.detect(tmpDir)

        assertThat(result!!.type).isEqualTo(DockerConfigType.DOCKER_COMPOSE)
        assertThat(result.composeFile).isEqualTo(compose)
    }

    @Test
    fun `detect prefers docker-compose demo over docker-compose yml`(@TempDir tmpDir: Path) {
        val demo = tmpDir.resolve("docker-compose.demo.yml")
        Files.writeString(demo, "services: {}")
        Files.writeString(tmpDir.resolve("docker-compose.yml"), "services: {}")

        val result = detector.detect(tmpDir)

        assertThat(result!!.composeFile).isEqualTo(demo)
    }
}
