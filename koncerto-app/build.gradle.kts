plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(project(":koncerto-workflow"))
    implementation(project(":koncerto-workspace"))
    implementation(project(":koncerto-agent"))
    implementation(project(":koncerto-linear"))
    implementation(project(":koncerto-orchestrator"))
    implementation(project(":koncerto-dashboard"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
