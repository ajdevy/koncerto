package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.isEmpty
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SecretRequirementDetectorTest {

    private val detector = SecretRequirementDetector()

    @Test
    fun `env template flags empty values as required and treats non-empty as defaults`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(".env.example"), """
            # comment
            DB_URL=postgresql://localhost/db
            SECRET_KEY=changeme-at-least-32-chars-long-here
            BREVO_API_KEY=
            DEBUG_TOKEN=
            EMAIL_FROM_ADDRESS=team@example.com
        """.trimIndent())

        assertThat(detector.detect(dir)).containsExactlyInAnyOrder("BREVO_API_KEY", "DEBUG_TOKEN")
    }

    @Test
    fun `no env template and no other declarations yields empty set`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("README.md"), "nothing to see here")
        assertThat(detector.detect(dir)).isEmpty()
    }

    @Test
    fun `spring placeholder without default is required but with default is not`(@TempDir dir: Path) {
        val resources = Files.createDirectories(dir.resolve("src/main/resources"))
        Files.writeString(resources.resolve("application.properties"), """
            app.token=${'$'}{APP_TOKEN}
            app.region=${'$'}{APP_REGION:us-east}
        """.trimIndent())

        assertThat(detector.detect(dir)).containsExactlyInAnyOrder("APP_TOKEN")
    }

    @Test
    fun `compose env reference without default is required`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("docker-compose.yml"), """
            services:
              app:
                image: app
                environment:
                  API_KEY: ${'$'}{API_KEY}
                  REGION: ${'$'}{REGION:-us}
        """.trimIndent())

        assertThat(detector.detect(dir)).containsExactlyInAnyOrder("API_KEY")
    }

    @Test
    fun `compose required markers colon-question and question are detected as required`(@TempDir dir: Path) {
        // ${'$'}{VAR:?err} and ${'$'}{VAR?err} are compose's explicit "must be set" syntax — the
        // strongest required signal — and must be flagged. ${'$'}{VAR:-def} / ${'$'}{VAR-def} are defaults.
        Files.writeString(dir.resolve("docker-compose.yml"), """
            services:
              app:
                environment:
                  BREVO_API_KEY: ${'$'}{BREVO_API_KEY:?must be set}
                  DEBUG_TOKEN: ${'$'}{DEBUG_TOKEN?required}
                  HOST: ${'$'}{HOST:-localhost}
                  PORT: ${'$'}{PORT-8080}
        """.trimIndent())

        assertThat(detector.detect(dir)).containsExactlyInAnyOrder("BREVO_API_KEY", "DEBUG_TOKEN")
    }

    @Test
    fun `empty value written as empty quotes is still required`(@TempDir dir: Path) {
        Files.writeString(dir.resolve(".env.sample"), "TOKEN=\"\"\n")
        assertThat(detector.detect(dir)).containsExactlyInAnyOrder("TOKEN")
    }
}
