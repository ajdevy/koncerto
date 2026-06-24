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
    implementation(project(":koncerto-metrics"))
    implementation(project(":koncerto-dashboard"))
    implementation(project(":koncerto-notifications"))
    implementation(project(":koncerto-demo"))
    implementation(project(":koncerto-deploy"))

    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.snakeyaml)
    implementation("com.sun.mail:jakarta.mail:2.0.1")

    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.playwright)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("ui")
    }
}

tasks.register<Test>("uiTest") {
    useJUnitPlatform {
        includeTags("ui")
    }
    group = "verification"
    description = "Runs Playwright UI tests against the dashboard"
}
