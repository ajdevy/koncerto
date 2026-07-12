package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test

class DockerfileGeneratorTest {

    private val generator = DockerfileGenerator()

    @Test
    fun `generate spring-boot dockerfile`() {
        val fw = FrameworkInfo("spring-boot", listOf(8080), "./gradlew build", "java -jar app.jar")
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("eclipse-temurin")
        assertThat(dockerfile).contains("EXPOSE 8080")
        assertThat(dockerfile).contains("./gradlew build")
    }

    @Test
    fun `generate node dockerfile`() {
        val fw = FrameworkInfo("node", listOf(3000), "npm install", "npm start")
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("node:20-alpine")
        assertThat(dockerfile).contains("npm install")
        assertThat(dockerfile).contains("npm start")
    }

    @Test
    fun `generate python dockerfile`() {
        val fw = FrameworkInfo("python", listOf(5000), "pip install .", "python app.py")
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("python:3.12-slim")
        assertThat(dockerfile).contains("EXPOSE 5000")
        assertThat(dockerfile).contains("python app.py")
    }

    @Test
    fun `generate python dockerfile installs the project dir when no build command is given`() {
        // buildCmd null → installTarget falls back to "." (install the project directory).
        val fw = FrameworkInfo("python", listOf(5000), null, null)
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("python:3.12-slim")
        assertThat(dockerfile).contains("--no-build-isolation")
        assertThat(dockerfile).contains("python app.py")
    }

    @Test
    fun `generate go dockerfile`() {
        val fw = FrameworkInfo("go", listOf(8080), "go build -o app .", "./app")
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("golang:1.22-alpine")
        assertThat(dockerfile).contains("go build -o app .")
    }

    @Test
    fun `generate generic dockerfile for unknown framework`() {
        val fw = FrameworkInfo("rust", listOf(9000), null, "./target/release/app")
        val dockerfile = generator.generate(fw)

        assertThat(dockerfile).contains("alpine:3.19")
        assertThat(dockerfile).contains("EXPOSE 9000")
        assertThat(dockerfile).contains("./target/release/app")
    }
}
