import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
}

allprojects {
    group = "com.flexsentlabs.koncerto"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "jacoco")

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            javaParameters.set(true)
        }
    }

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
            csv.required.set(true)
        }
    }

    // Coverage verification - fails if line coverage < 75% per module
    val hasTests = tasks.matching { it.name == "test" }.isNotEmpty()

    if (hasTests && jacocoEnabled) {
        tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
            group = "verification"
            description = "Verifies code coverage meets minimum thresholds"

            executionData(tasks.named("test"))

            violationRules {
                rule {
                    limit {
                        counter = "LINE"
                        value = "COVEREDRATIO"
                        minimum = 0.75.toBigDecimal()
                    }
                }
            }
        }

        tasks.matching { it.name == "test" }.configureEach {
            finalizedBy("jacocoCoverageVerification")
        }
    }
}
