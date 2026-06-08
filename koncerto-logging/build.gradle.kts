plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}

