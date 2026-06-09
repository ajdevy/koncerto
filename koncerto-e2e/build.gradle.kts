plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":koncerto-core"))
    testImplementation(project(":koncerto-logging"))
    testImplementation(project(":koncerto-agent"))
    testImplementation(project(":koncerto-workspace"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("e2e")
    }
}

tasks.register<Test>("e2eTest") {
    useJUnitPlatform {
        includeTags("e2e")
    }
    val e2eOpencode = providers.systemProperty("koncerto.e2e.opencode")
    if (e2eOpencode.isPresent) {
        systemProperty("koncerto.e2e.opencode", e2eOpencode.get())
    }
    inputs.property("koncerto.e2e.opencode", e2eOpencode.orElse("false"))
    group = "verification"
    description = "Runs end-to-end tests (requires opencode CLI)"
}
