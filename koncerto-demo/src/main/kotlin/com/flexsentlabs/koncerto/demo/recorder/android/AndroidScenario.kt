package com.flexsentlabs.koncerto.demo.recorder.android

/**
 * One step of an Android demo scenario.
 *
 * This is Koncerto's own vocabulary, not Maestro's. The model authors against a small, documented
 * set of actions and [MaestroFlowBuilder] translates them, so a scenario is validated here — before
 * a device is touched — instead of failing halfway through a recording on a syntax the model
 * guessed at.
 */
sealed interface AndroidStep {
    /** Human narration; carried into the flow as a comment so the recording is self-explaining. */
    val note: String

    /** Cold-starts the app under test. */
    data class Launch(val clearState: Boolean = true, override val note: String = "") : AndroidStep

    /** Taps the first element matching [text] or [id]. */
    data class Tap(
        val text: String? = null,
        val id: String? = null,
        override val note: String = ""
    ) : AndroidStep

    /** Types into the focused field, optionally tapping [into] first. */
    data class Input(
        val value: String,
        val into: String? = null,
        override val note: String = ""
    ) : AndroidStep

    /** Fails the demo when the element is absent — this is what makes a recording evidence. */
    data class AssertVisible(
        val text: String? = null,
        val id: String? = null,
        override val note: String = ""
    ) : AndroidStep

    data class Scroll(val direction: String = "down", override val note: String = "") : AndroidStep

    data class Back(override val note: String = "") : AndroidStep

    data class Wait(val ms: Int, override val note: String = "") : AndroidStep

    data class Screenshot(val name: String, override val note: String = "") : AndroidStep
}

/** A parsed, validated Android demo scenario. */
data class AndroidScenario(
    val appId: String,
    val description: String,
    val steps: List<AndroidStep>
) {
    /** Steps that can fail the run, as opposed to navigation. */
    val assertionCount: Int get() = steps.count { it is AndroidStep.AssertVisible }
}
