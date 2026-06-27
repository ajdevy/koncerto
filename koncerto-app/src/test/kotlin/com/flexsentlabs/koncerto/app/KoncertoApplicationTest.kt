package com.flexsentlabs.koncerto.app

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.ComponentScan

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

}
