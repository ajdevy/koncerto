plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(project(":koncerto-logging"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}