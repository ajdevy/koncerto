package com.flexsentlabs.koncerto.workflow

import liqp.Template
import java.time.Instant
import java.util.regex.Pattern

object PromptRenderer {

    private val VAR_PATTERN = Pattern.compile("""\{\{\s*([\w.]+)\s*\}\}""")

    fun render(template: String, context: Map<String, Any?>): String {
        if (template.isBlank()) return ""
        // Strict mode: validate all variables exist in context
        validateVariables(template, context)
        
        return try {
            val tpl = Template.parse(template)
            val rendered = tpl.render(toStringMap(context))
            rendered
        } catch (e: Exception) {
            throw IllegalStateException("template_render_error: ${e.message}", e)
        }
    }

    private fun validateVariables(template: String, context: Map<String, Any?>) {
        val matcher = VAR_PATTERN.matcher(template)
        while (matcher.find()) {
            val varPath = matcher.group(1)
            if (!resolveVariable(context, varPath)) {
                throw IllegalStateException("template_render_error: unresolved variable '$varPath'")
            }
        }
    }

    private fun resolveVariable(context: Map<String, Any?>, path: String): Boolean {
        var current: Any? = context
        for (part in path.split(".")) {
            when (current) {
                is Map<*, *> -> current = current[part]
                else -> return false
            }
            if (current == null) return false
        }
        return true
    }

    private fun toStringMap(context: Map<String, Any?>): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        context.forEach { (k, v) -> out[k] = stringifyValue(v) }
        return out
    }

    private fun stringifyValue(v: Any?): Any = when (v) {
        null -> ""
        is Map<*, *> -> v.entries.associate { (k, vv) -> k.toString() to stringifyValue(vv) }
        is List<*> -> v.map { stringifyValue(it) }
        is Instant -> v.toString()
        else -> v
    }
}