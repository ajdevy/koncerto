package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import com.flexsentlabs.koncerto.dashboard.ReviewController
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.zip.ZipFile

class SpringOptionalRuntimeTest {
    @Test
    fun `packaged runtime supports absent optional Kotlin dependencies`() {
        val jar = requireNotNull(System.getProperty("koncerto.test.bootJar"))
        ZipFile(jar).use { archive ->
            assertTrue(archive.entries().asSequence().any {
                it.name.startsWith("BOOT-INF/lib/kotlin-reflect-") && it.name.endsWith(".jar")
            }, "bootJar must contain kotlin-reflect")
        }
        AnnotationConfigApplicationContext(OptionalWiring::class.java).use { context ->
            assertNull(context.getBean(OptionalConsumer::class.java).storage)
        }
    }

    @Test
    fun `Spring can create the review controller without optional review metrics`() {
        AnnotationConfigApplicationContext(ReviewController::class.java).use { context ->
            assertNotNull(context.getBean(ReviewController::class.java))
        }
    }

    class OptionalConsumer(val storage: DemoStorage?)

    @Configuration(proxyBeanMethods = false)
    class OptionalWiring {
        @Bean
        fun optionalConsumer(storage: DemoStorage?): OptionalConsumer = OptionalConsumer(storage)
    }
}
