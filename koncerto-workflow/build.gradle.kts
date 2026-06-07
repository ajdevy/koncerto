plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":koncerto-core"))
    implementation(libs.snakeyaml)
    implementation(libs.liqp)

    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.assertk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
