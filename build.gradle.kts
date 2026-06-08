plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.anomaly.koncerto"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }

    val jacocoEnabled = providers.gradleProperty("jacoco").isPresent()

    tasks.withType<JacocoReport>().configureEach {
        dependsOn("test")
        onlyIf { jacocoEnabled }

        reports {
            html.required.set(true)
            xml.required.set(true)
            csv.required.set(false)
        }
    }
}
