package com.flexsentlabs.koncerto.demo.recorder.api

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/**
 * Parses the `api_scenario` YAML block an agent authors for a server-side demo.
 *
 * The input is whatever the scenario file holds, which in practice is model output: the block may be
 * wrapped in markdown fences and preceded by prose. Extraction mirrors the browser scenario parser's
 * tolerance so the two paths fail (or succeed) on the same inputs.
 *
 * Every failure returns null rather than throwing — a malformed scenario is a recoverable condition
 * the caller reports as a recording failure, which feeds the existing scenario-repair loop.
 */
object ApiScenarioParser {

    private const val ROOT_KEY = "api_scenario"

    fun parse(raw: String): ApiScenario? {
        val yaml = extractBlock(raw) ?: return null
        val root = try {
            Yaml(SafeConstructor(LoaderOptions())).load<Any?>(yaml)
        } catch (_: Exception) {
            return null
        }
        val scenario = ((root as? Map<*, *>)?.get(ROOT_KEY) as? Map<*, *>) ?: return null
        val rawSteps = scenario["steps"] as? List<*> ?: return null
        val steps = rawSteps.mapNotNull { parseStep(it) }
        if (steps.none { it is ApiStep.Request }) return null
        return ApiScenario(
            description = (scenario["description"] as? String)?.trim().orEmpty(),
            steps = steps
        )
    }

    /**
     * Narrows [raw] to the YAML document containing the root key. Drops markdown fences and any
     * prose before `api_scenario:`; keeps everything after it, since trailing model chatter is
     * indented-sensitive and rare enough not to be worth guessing at.
     */
    internal fun extractBlock(raw: String): String? {
        val lines = raw.lines().filterNot { it.trimStart().startsWith("```") }
        val start = lines.indexOfFirst { it.trimStart().startsWith("$ROOT_KEY:") }
        if (start < 0) return null
        // Models routinely indent the whole block to sit inside a fence or a list item. Re-anchoring
        // it to column 0 keeps that output parseable instead of failing on presentation.
        return lines.subList(start, lines.size).joinToString("\n").trimIndent().takeIf { it.isNotBlank() }
    }

    private fun parseStep(node: Any?): ApiStep? {
        val map = node as? Map<*, *> ?: return null
        val note = (map["note"] as? String)?.trim().orEmpty()
        // `action` is optional: a step carrying a `path` is a request, a step carrying only a note is
        // narration. Being lenient here costs nothing and salvages otherwise-valid model output.
        return when ((map["action"] as? String)?.trim()?.lowercase() ?: inferAction(map)) {
            "request" -> parseRequest(map, note)
            "wait" -> ApiStep.Wait(ms = asInt(map["ms"]) ?: DEFAULT_WAIT_MS, note = note)
            "note" -> note.takeIf { it.isNotEmpty() }?.let { ApiStep.Note(it) }
            else -> null
        }
    }

    private fun inferAction(map: Map<*, *>): String = when {
        map["path"] != null -> "request"
        map["ms"] != null -> "wait"
        map["note"] != null -> "note"
        else -> ""
    }

    private fun parseRequest(map: Map<*, *>, note: String): ApiStep.Request? {
        val path = (map["path"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return ApiStep.Request(
            method = (map["method"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "GET",
            path = path,
            headers = asStringMap(map["headers"]),
            body = asBody(map["body"]),
            expectStatus = asInt(map["expect_status"]),
            expectContains = (map["expect_contains"] as? String)?.takeIf { it.isNotEmpty() },
            save = asStringMap(map["save"]),
            note = note
        )
    }

    /** A body may be written as a YAML mapping (the natural way to express JSON) or as a string. */
    private fun asBody(node: Any?): String? = when (node) {
        null -> null
        is String -> node.takeIf { it.isNotBlank() }
        is Map<*, *>, is List<*> -> JsonEncoding.encode(node)
        else -> node.toString()
    }

    private fun asStringMap(node: Any?): Map<String, String> {
        val map = node as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            key to (v?.toString() ?: "")
        }.toMap()
    }

    private fun asInt(node: Any?): Int? = when (node) {
        is Int -> node
        is Number -> node.toInt()
        is String -> node.trim().toIntOrNull()
        else -> null
    }

    private const val DEFAULT_WAIT_MS = 1000
}
