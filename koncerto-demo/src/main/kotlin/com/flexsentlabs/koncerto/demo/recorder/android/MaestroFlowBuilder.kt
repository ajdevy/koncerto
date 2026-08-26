package com.flexsentlabs.koncerto.demo.recorder.android

/**
 * Translates a validated [AndroidScenario] into a Maestro flow file.
 *
 * Maestro drives the app: it resolves elements by visible text or resource id, waits for them, and
 * retries — which is what makes an LLM-authored mobile flow survive contact with a real device.
 * Appium and Espresso both need per-app scaffolding compiled into the project, which a generic
 * pipeline cannot assume, and the same flow format runs on an iOS simulator, so one vocabulary
 * covers both mobile platforms.
 *
 * Pure string building with no I/O, so the emitted flow is unit-testable without a device.
 */
object MaestroFlowBuilder {

    fun build(scenario: AndroidScenario): String = buildString {
        appendLine("# ${scenario.description.ifBlank { "Koncerto demo" }}")
        appendLine("appId: ${scenario.appId}")
        appendLine("---")
        scenario.steps.forEach { step ->
            if (step.note.isNotBlank()) appendLine("# ${step.note.replace("\n", " ")}")
            appendLine(render(step))
        }
    }

    private fun render(step: AndroidStep): String = when (step) {
        is AndroidStep.Launch ->
            if (step.clearState) "- launchApp:\n    clearState: true" else "- launchApp"
        is AndroidStep.Tap -> "- tapOn:\n" + selector(step.text, step.id)
        is AndroidStep.Input -> buildString {
            step.into?.let { appendLine("- tapOn:\n    text: ${quote(it)}") }
            append("- inputText: ${quote(step.value)}")
        }
        is AndroidStep.AssertVisible -> "- assertVisible:\n" + selector(step.text, step.id)
        is AndroidStep.Scroll -> scroll(step.direction)
        is AndroidStep.Back -> "- back"
        // Maestro has no raw sleep; an extendedWaitUntil on an always-true condition is the
        // idiomatic way to hold the screen so a viewer can read what just happened.
        is AndroidStep.Wait -> "- waitForAnimationToEnd:\n    timeout: ${step.ms.coerceIn(0, MAX_WAIT_MS)}"
        is AndroidStep.Screenshot -> "- takeScreenshot: ${quote(step.name)}"
    }

    /**
     * Maestro's `scroll` takes no arguments — `scroll: {direction: ...}` is rejected outright as an
     * unknown property, verified against the CLI. Directional movement is `swipe`, and the two are
     * inverted: the scenario's `direction` is where the *content* should move, while a swipe names
     * what the finger does. Scrolling down (the common "show me more" case) is plain `scroll`;
     * everything else swipes the opposite way.
     */
    private fun scroll(direction: String): String = when (direction) {
        "up" -> "- swipe:\n    direction: DOWN"
        "left" -> "- swipe:\n    direction: RIGHT"
        "right" -> "- swipe:\n    direction: LEFT"
        else -> "- scroll"
    }

    /** Prefers a resource id when the scenario gives one — it survives copy changes. */
    private fun selector(text: String?, id: String?): String =
        if (id != null) "    id: ${quote(id)}" else "    text: ${quote(text.orEmpty())}"

    /**
     * Double-quotes a YAML scalar, escaping what YAML itself would otherwise reinterpret. Demo text
     * routinely contains quotes and colons, and an unescaped one silently changes the flow's shape
     * rather than failing loudly.
     */
    internal fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

    private const val MAX_WAIT_MS = 10_000
}
