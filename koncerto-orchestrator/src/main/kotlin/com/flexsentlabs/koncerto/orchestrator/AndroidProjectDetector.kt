package com.flexsentlabs.koncerto.orchestrator

import java.nio.file.Files
import java.nio.file.Path

/**
 * Decides whether a checked-out workspace is an Android application project.
 *
 * Unlike the browser/API split, this cannot be read off a running deployment — an Android app has no
 * URL to crawl or probe. The repository itself is the only evidence available, so detection looks
 * for the marks only an Android *application* leaves: the application plugin, or a manifest carrying
 * a launcher activity.
 *
 * Library modules are deliberately not enough. `com.android.library` produces an AAR with nothing to
 * install or launch, so treating one as an Android demo would route a ticket to a recorder that can
 * never run it.
 */
object AndroidProjectDetector {

    private val BUILD_FILES = listOf("build.gradle.kts", "build.gradle")
    private const val APPLICATION_PLUGIN = "com.android.application"
    // app/src/main/AndroidManifest.xml is four levels down in a conventional layout, and a
    // multi-module repo adds one more before that.
    private const val MAX_DEPTH = 5

    fun isAndroidApp(root: Path): Boolean = runCatching {
        Files.walk(root, MAX_DEPTH).use { paths ->
            paths.anyMatch { path ->
                val name = path.fileName?.toString() ?: return@anyMatch false
                when {
                    name in BUILD_FILES -> declaresApplicationPlugin(path)
                    name == "AndroidManifest.xml" -> declaresLauncherActivity(path)
                    else -> false
                }
            }
        }
    }.getOrDefault(false)

    private fun declaresApplicationPlugin(path: Path): Boolean =
        runCatching { Files.readString(path).contains(APPLICATION_PLUGIN) }.getOrDefault(false)

    /**
     * A manifest alone does not make an app — test and library modules ship one too. The launcher
     * intent filter is what says there is something a person could open and therefore demo.
     */
    private fun declaresLauncherActivity(path: Path): Boolean = runCatching {
        val text = Files.readString(path)
        text.contains("android.intent.category.LAUNCHER")
    }.getOrDefault(false)

    /** The application id to launch, read from the build file, or null when it cannot be determined. */
    fun applicationId(root: Path): String? = runCatching {
        Files.walk(root, MAX_DEPTH).use { paths ->
            paths.filter { it.fileName?.toString() in BUILD_FILES }
                .map { runCatching { Files.readString(it) }.getOrDefault("") }
                .map { extractApplicationId(it) }
                .filter { it != null }
                .findFirst()
                .orElse(null)
        }
    }.getOrNull()

    /** Matches `applicationId = "com.x"` (kts) and `applicationId "com.x"` (groovy). */
    internal fun extractApplicationId(buildFile: String): String? =
        Regex("""applicationId\s*=?\s*["']([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+)["']""")
            .find(buildFile)?.groupValues?.get(1)
}
