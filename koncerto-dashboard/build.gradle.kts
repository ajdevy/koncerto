plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-metrics"))
    implementation(project(":koncerto-orchestrator"))
    implementation(project(":koncerto-workflow"))
    implementation(project(":koncerto-agent"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(project(":koncerto-linear"))
    testImplementation(project(":koncerto-logging"))
    testImplementation(project(":koncerto-workspace"))
    testImplementation(project(":koncerto-notifications"))
}

