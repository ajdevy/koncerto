package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AndroidProjectDetectorTest {

    private fun write(root: Path, relative: String, content: String) {
        val target = root.resolve(relative)
        Files.createDirectories(target.parent)
        Files.writeString(target, content)
    }

    @Test
    fun `the application plugin identifies an Android app`(@TempDir root: Path) {
        write(root, "app/build.gradle.kts", """plugins { id("com.android.application") }""")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isTrue()
    }

    @Test
    fun `groovy build files are recognised too`(@TempDir root: Path) {
        write(root, "app/build.gradle", "apply plugin: 'com.android.application'")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isTrue()
    }

    @Test
    fun `a library module is not an app`(@TempDir root: Path) {
        // com.android.library produces an AAR — nothing to install or launch, so routing a ticket
        // to the mobile recorder would give it a demo it can never run.
        write(root, "core/build.gradle.kts", """plugins { id("com.android.library") }""")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isFalse()
    }

    @Test
    fun `a manifest counts only when it declares a launcher activity`(@TempDir root: Path) {
        write(root, "app/src/main/AndroidManifest.xml",
            """<manifest><application><activity><intent-filter>
               <category android:name="android.intent.category.LAUNCHER"/>
               </intent-filter></activity></application></manifest>""")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isTrue()
    }

    @Test
    fun `a manifest without a launcher activity is not enough`(@TempDir root: Path) {
        // Test and library modules ship manifests too.
        write(root, "app/src/androidTest/AndroidManifest.xml",
            "<manifest><application/></manifest>")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isFalse()
    }

    @Test
    fun `a non-Android repository is rejected`(@TempDir root: Path) {
        write(root, "build.gradle.kts", """plugins { kotlin("jvm") }""")
        write(root, "package.json", """{"name":"web"}""")

        assertThat(AndroidProjectDetector.isAndroidApp(root)).isFalse()
    }

    @Test
    fun `an unreadable or missing root degrades to not-an-app`() {
        assertThat(AndroidProjectDetector.isAndroidApp(Path.of("/definitely/not/here"))).isFalse()
    }

    @Test
    fun `extracts the application id from both build syntaxes`() {
        assertThat(AndroidProjectDetector.extractApplicationId("""applicationId = "com.example.app""""))
            .isEqualTo("com.example.app")
        assertThat(AndroidProjectDetector.extractApplicationId("""applicationId "com.example.app""""))
            .isEqualTo("com.example.app")
        assertThat(AndroidProjectDetector.extractApplicationId("""applicationId='com.a.b.c'"""))
            .isEqualTo("com.a.b.c")
    }

    @Test
    fun `rejects an application id that is not a package name`() {
        assertThat(AndroidProjectDetector.extractApplicationId("""applicationId = "notapackage"""")).isNull()
        assertThat(AndroidProjectDetector.extractApplicationId("no application id here")).isNull()
    }

    @Test
    fun `applicationId is read from the build file on disk`(@TempDir root: Path) {
        write(root, "app/build.gradle.kts",
            """android { defaultConfig { applicationId = "com.example.demoapp" } }""")

        assertThat(AndroidProjectDetector.applicationId(root)).isEqualTo("com.example.demoapp")
    }

    @Test
    fun `applicationId is null when no build file declares one`(@TempDir root: Path) {
        write(root, "app/build.gradle.kts", """plugins { id("com.android.application") }""")

        assertThat(AndroidProjectDetector.applicationId(root)).isNull()
        assertThat(AndroidProjectDetector.applicationId(Path.of("/definitely/not/here"))).isNull()
    }
}
