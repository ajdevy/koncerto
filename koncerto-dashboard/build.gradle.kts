plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-orchestrator"))
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.spring.boot.starter.test)
}

