plugins {
    kotlin("jvm")
}

dependencies {
    testImplementation(project(":koncerto-core"))
    testImplementation(project(":koncerto-logging"))
    testImplementation(project(":koncerto-agent"))
    testImplementation(project(":koncerto-workspace"))
    testImplementation(project(":koncerto-linear"))
    testImplementation(project(":koncerto-orchestrator"))
    testImplementation(project(":koncerto-workflow"))
    testImplementation(project(":koncerto-metrics"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(project(":koncerto-notifications"))
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
    val e2eCodex = providers.systemProperty("koncerto.e2e.codex")
    if (e2eCodex.isPresent) {
        systemProperty("koncerto.e2e.codex", e2eCodex.get())
    }
    inputs.property("koncerto.e2e.codex", e2eCodex.orElse("false"))
    // Review-pipeline e2e against a real free model (FreeModelReviewE2eTest).
    val e2eReview = providers.systemProperty("koncerto.e2e.review")
    if (e2eReview.isPresent) {
        systemProperty("koncerto.e2e.review", e2eReview.get())
    }
    inputs.property("koncerto.e2e.review", e2eReview.orElse("false"))
    group = "verification"
    description = "Runs end-to-end tests (requires opencode and/or codex CLI)"
}
