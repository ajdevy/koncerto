package com.flexsentlabs.koncerto.demo.recorder.api

/**
 * One executable step of an API demo scenario. Unlike the browser vocabulary (which the recorder
 * container's embedded JS interprets), API steps are parsed here in Kotlin so the whole scenario is
 * validated — and unit-testable — before any container starts.
 */
sealed interface ApiStep {
    /** Human narration printed above the step in the recording, so the video explains itself. */
    val note: String

    /** A narration-only beat: no request, just an on-screen line of explanation. */
    data class Note(override val note: String) : ApiStep

    /** A deliberate pause, so a viewer can read what just happened. */
    data class Wait(val ms: Int, override val note: String = "") : ApiStep

    /**
     * A real HTTP call issued with curl against the deployed target.
     *
     * [expectStatus] and [expectContains] are hard assertions: a mismatch fails the recording so the
     * pipeline's repair/escalation loop sees it, rather than publishing a video of a broken feature
     * as if it passed. [save] captures values out of the JSON response body (`name` -> dotted path)
     * for later steps to interpolate as `{{name}}`.
     */
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val expectStatus: Int? = null,
        val expectContains: String? = null,
        val save: Map<String, String> = emptyMap(),
        override val note: String = ""
    ) : ApiStep
}

/** A parsed, validated API demo scenario. */
data class ApiScenario(
    val description: String,
    val steps: List<ApiStep>
) {
    val requestCount: Int get() = steps.count { it is ApiStep.Request }
}
