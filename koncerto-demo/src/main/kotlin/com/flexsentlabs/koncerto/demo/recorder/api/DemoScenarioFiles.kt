package com.flexsentlabs.koncerto.demo.recorder.api

import java.io.File

/**
 * The staging-file naming shared by the orchestrator (which writes demo scenarios) and the recording
 * service (which reads them).
 *
 * The two live in different modules and only agree by convention, so the convention is defined once
 * here rather than as matching string literals on both sides. The kind of scenario staged is also
 * what selects the recorder: a ticket with an API scenario is a server-side demo, one with a browser
 * scenario is a web demo. That keeps platform choice with the component that understands the ticket
 * instead of requiring a platform to be threaded through every call in between.
 *
 * Each scenario is written under both the issue's id and its human identifier, because callers hold
 * one or the other depending on where in the pipeline they sit.
 */
object DemoScenarioFiles {

    const val BROWSER_SUFFIX = "-scenario.yaml"
    const val API_SUFFIX = "-api-scenario.yaml"
    const val CREDENTIALS_SUFFIX = "-credentials.env"

    fun browserScenario(dir: String, key: String): File = File(dir, "$key$BROWSER_SUFFIX")

    fun apiScenario(dir: String, key: String): File = File(dir, "$key$API_SUFFIX")

    fun credentials(dir: String, key: String): File = File(dir, "$key$CREDENTIALS_SUFFIX")

    /** The first of [keys] that has a file of this kind staged, or null when none does. */
    fun findExisting(dir: String, keys: List<String>, suffix: String): File? = keys
        .asSequence()
        .filter { it.isNotBlank() }
        .map { File(dir, "$it$suffix") }
        .firstOrNull { it.isFile }
}
