package com.flexsentlabs.koncerto.demo.recorder.android

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses the `android_scenario` YAML block an agent authors for a mobile demo.
 *
 * Mirrors the API scenario parser: tolerant of markdown fences and indentation, and returning null
 * for anything unusable rather than throwing, so a malformed scenario is a recoverable condition the
 * caller reports as a recording failure and feeds into the existing repair loop.
 */
object AndroidScenarioParser {

    private const val ROOT_KEY = "android_scenario"
    private const val DEFAULT_WAIT_MS = 1000
    private val DIRECTIONS = setOf("up", "down", "left", "right")

    fun parse(raw: String): AndroidScenario? {
        val yaml = extractBlock(raw) ?: return null
        val root = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml)
        } catch (_: Exception) {
            return null
        }
        val scenario = ((root as? Map<*, *>)?.get(ROOT_KEY) as? Map<*, *>) ?: return null

        // Without an application id there is nothing to launch and Maestro cannot even start, so a
        // scenario missing it is rejected here rather than failing on the device.
        val appId = (scenario["appId"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val rawSteps = scenario["steps"] as? List<*> ?: return null
        val steps = rawSteps.mapNotNull { parseStep(it) }
        if (steps.none { it !is AndroidStep.Wait }) return null

        return AndroidScenario(
            appId = appId,
            description = (scenario["description"] as? String)?.trim().orEmpty(),
            steps = steps
        )
    }

    internal fun extractBlock(raw: String): String? {
        val lines = raw.lines().filterNot { it.trimStart().startsWith("```") }
        val start = lines.indexOfFirst { it.trimStart().startsWith("$ROOT_KEY:") }
        if (start < 0) return null
        return lines.subList(start, lines.size).joinToString("\n").trimIndent().takeIf { it.isNotBlank() }
    }

    private fun parseStep(node: Any?): AndroidStep? {
        val map = node as? Map<*, *> ?: return null
        val note = (map["note"] as? String)?.trim().orEmpty()
        val text = (map["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val id = (map["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        return when ((map["action"] as? String)?.trim()?.lowercase()) {
            "launch" -> AndroidStep.Launch(clearState = map["clearState"] as? Boolean ?: true, note = note)
            "tap" -> if (text == null && id == null) null else AndroidStep.Tap(text, id, note)
            "input" -> {
                val value = (map["value"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
                AndroidStep.Input(value, (map["into"] as? String)?.trim()?.takeIf { it.isNotEmpty() }, note)
            }
            "assert_visible" -> if (text == null && id == null) null else AndroidStep.AssertVisible(text, id, note)
            "scroll" -> AndroidStep.Scroll(
                direction = (map["direction"] as? String)?.trim()?.lowercase()?.takeIf { it in DIRECTIONS } ?: "down",
                note = note
            )
            "back" -> AndroidStep.Back(note)
            "wait" -> AndroidStep.Wait(asInt(map["ms"]) ?: DEFAULT_WAIT_MS, note)
            "screenshot" -> AndroidStep.Screenshot(
                name = (map["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "screenshot",
                note = note
            )
            else -> null
        }
    }

    private fun asInt(node: Any?): Int? = when (node) {
        is Int -> node
        is Number -> node.toInt()
        is String -> node.trim().toIntOrNull()
        else -> null
    }
}
