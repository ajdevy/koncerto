plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(project(":koncerto-workflow"))
    implementation(project(":koncerto-workspace"))
    implementation(project(":koncerto-linear"))
    implementation(project(":koncerto-notifications"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.spring.boot.starter.actuator)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}
