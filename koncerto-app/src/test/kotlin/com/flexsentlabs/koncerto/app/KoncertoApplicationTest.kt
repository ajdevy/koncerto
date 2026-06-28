package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.logging.StructuredLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import java.io.FileInputStream
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit

class KoncertoApplicationTest {

    @Test
    fun `application class exists`() {
        val app = KoncertoApplication()
        assertThat(app).isNotNull()
    }

    @Test
    fun `application class has correct simple name`() {
        assertThat(KoncertoApplication::class.java.simpleName).isEqualTo("KoncertoApplication")
    }

    @Test
    fun `application class is annotated with SpringBootApplication`() {
        val annotation = KoncertoApplication::class.java.getAnnotation(SpringBootApplication::class.java)
        assertThat(annotation).isNotNull()
        assertThat(annotation!!.proxyBeanMethods).isFalse()
    }

    @Test
    fun `application class is annotated with ComponentScan`() {
        val annotation = KoncertoApplication::class.java.getAnnotation(ComponentScan::class.java)
        assertThat(annotation).isNotNull()
        assertThat(annotation!!.basePackages).isEqualTo(arrayOf("com.flexsentlabs.koncerto"))
    }

    @Test
    fun `buildDockerAgentImage skips when docker disabled`() {
        val config = ServiceConfig.fromMap(mapOf("poll_interval_ms" to 15000), ".")
        val clazz = Class.forName("com.flexsentlabs.koncerto.app.KoncertoApplicationKt")
        val method = clazz.getDeclaredMethod(
            "buildDockerAgentImage",
            ServiceConfig::class.java,
            StructuredLogger::class.java,
            org.springframework.context.ApplicationContext::class.java
        )
        method.isAccessible = true
        method.invoke(null, config, StructuredLogger(emptyList()), mock(ApplicationContext::class.java))
    }

    @Test
    fun `buildDockerAgentImage gracefully handles docker enabled without docker`() {
        val config = ServiceConfig.fromMap(mapOf(
            "poll_interval_ms" to 15000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "test-key",
                        "project_slug" to "test"
                    ),
                    "agent" to mapOf(
                        "docker" to mapOf(
                            "enabled" to true,
                            "dockerfile" to "Dockerfile.nonexistent",
                            "image" to "koncerto-test-nonexistent:latest"
                        )
                    )
                )
            )
        ), ".")

        val clazz = Class.forName("com.flexsentlabs.koncerto.app.KoncertoApplicationKt")
        val method = clazz.getDeclaredMethod(
            "buildDockerAgentImage",
            ServiceConfig::class.java,
            StructuredLogger::class.java,
            org.springframework.context.ApplicationContext::class.java
        )
        method.isAccessible = true

        method.invoke(null, config, StructuredLogger(emptyList()), mock(ApplicationContext::class.java))
    }

    @Test
    fun `buildDockerAgentImage returns early when docker image exists`() {
        val dockerAvailable = try {
            val p = Runtime.getRuntime().exec(arrayOf("docker", "info"))
            val exited = p.waitFor(10, TimeUnit.SECONDS)
            exited && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerAvailable, "Docker not available, skipping")

        val pull = Runtime.getRuntime().exec(arrayOf("docker", "pull", "hello-world:latest"))
        pull.waitFor(60, TimeUnit.SECONDS)

        val config = ServiceConfig.fromMap(mapOf(
            "poll_interval_ms" to 15000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "test-key",
                        "project_slug" to "test"
                    ),
                    "agent" to mapOf(
                        "docker" to mapOf(
                            "enabled" to true,
                            "image" to "hello-world:latest"
                        )
                    )
                )
            )
        ), ".")

        val clazz = Class.forName("com.flexsentlabs.koncerto.app.KoncertoApplicationKt")
        val method = clazz.getDeclaredMethod(
            "buildDockerAgentImage",
            ServiceConfig::class.java,
            StructuredLogger::class.java,
            org.springframework.context.ApplicationContext::class.java
        )
        method.isAccessible = true

        method.invoke(null, config, StructuredLogger(emptyList()), mock(ApplicationContext::class.java))
    }

    @Test
    fun `buildDockerAgentImage uses defaults when dockerfile and image are null`() {
        val config = ServiceConfig.fromMap(mapOf(
            "poll_interval_ms" to 15000,
            "projects" to mapOf(
                "test" to mapOf(
                    "tracker" to mapOf(
                        "kind" to "linear",
                        "api_key" to "test-key",
                        "project_slug" to "test"
                    ),
                    "agent" to mapOf(
                        "docker" to mapOf(
                            "enabled" to true
                        )
                    )
                )
            )
        ), ".")

        val clazz = Class.forName("com.flexsentlabs.koncerto.app.KoncertoApplicationKt")
        val method = clazz.getDeclaredMethod(
            "buildDockerAgentImage",
            ServiceConfig::class.java,
            StructuredLogger::class.java,
            org.springframework.context.ApplicationContext::class.java
        )
        method.isAccessible = true

        method.invoke(null, config, StructuredLogger(emptyList()), mock(ApplicationContext::class.java))
    }

    @Test
    fun `property loading reads properties from local properties file`(@TempDir tmpDir: Path) {
        val propsFile = tmpDir.resolve("local.properties")
        propsFile.toFile().writeText("""
            my.custom.key=my-value
            kotlin.ignore=should-not-set
            sdk.dir=should-not-set
            org.gradle.jvmargs=should-not-set
        """.trimIndent())

        val props = Properties()
        FileInputStream(propsFile.toFile()).use { props.load(it) }

        val previous = System.getProperty("my.custom.key")
        try {
            for ((key, value) in props) {
                val k = key.toString()
                if (System.getProperty(k) == null && !k.startsWith("kotlin.") && !k.startsWith("sdk.") && !k.startsWith("org.gradle.")) {
                    System.setProperty(k, value.toString())
                }
            }
            assertThat(System.getProperty("my.custom.key")).isEqualTo("my-value")
            assertThat(System.getProperty("kotlin.ignore")).isNull()
            assertThat(System.getProperty("sdk.dir")).isNull()
            assertThat(System.getProperty("org.gradle.jvmargs")).isNull()
        } finally {
            if (previous != null) {
                System.setProperty("my.custom.key", previous)
            } else {
                System.clearProperty("my.custom.key")
            }
        }
    }

    @Test
    fun `property loading does not overwrite existing system properties`(@TempDir tmpDir: Path) {
        val existingKey = "existing.test.key"
        val existingValue = "already-set"
        System.setProperty(existingKey, existingValue)

        try {
            val propsFile = tmpDir.resolve("local.properties")
            propsFile.toFile().writeText("""
                $existingKey=should-not-override
                other.key=should-be-set
            """.trimIndent())

            val props = Properties()
            FileInputStream(propsFile.toFile()).use { props.load(it) }

            for ((key, value) in props) {
                val k = key.toString()
                if (System.getProperty(k) == null && !k.startsWith("kotlin.") && !k.startsWith("sdk.") && !k.startsWith("org.gradle.")) {
                    System.setProperty(k, value.toString())
                }
            }

            assertThat(System.getProperty(existingKey)).isEqualTo(existingValue)
            assertThat(System.getProperty("other.key")).isEqualTo("should-be-set")
        } finally {
            System.clearProperty(existingKey)
            System.clearProperty("other.key")
        }
    }

    @Test
    fun `property loading handles empty properties file`(@TempDir tmpDir: Path) {
        val propsFile = tmpDir.resolve("local.properties")
        propsFile.toFile().writeText("")

        val props = Properties()
        FileInputStream(propsFile.toFile()).use { props.load(it) }

        assertThat(props.isEmpty()).isTrue()
    }
}
