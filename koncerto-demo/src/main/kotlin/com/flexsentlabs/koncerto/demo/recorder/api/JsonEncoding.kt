package com.flexsentlabs.koncerto.demo.recorder.api

/**
 * Minimal JSON writer for the plain maps/lists snakeyaml produces.
 *
 * kotlinx.serialization is on the classpath but only encodes typed models; a scenario body is an
 * arbitrary untyped tree, so encoding it directly is both shorter and free of reflection surprises.
 */
internal object JsonEncoding {

    fun encode(node: Any?): String = buildString { write(node, this) }

    private fun write(node: Any?, out: StringBuilder) {
        when (node) {
            null -> out.append("null")
            is Boolean, is Int, is Long, is Short, is Byte -> out.append(node.toString())
            is Double -> out.append(if (node.isFinite()) node.toString() else "null")
            is Float -> out.append(if (node.isFinite()) node.toString() else "null")
            is Map<*, *> -> {
                out.append('{')
                node.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    quote(k?.toString().orEmpty(), out)
                    out.append(':')
                    write(v, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                node.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    write(v, out)
                }
                out.append(']')
            }
            else -> quote(node.toString(), out)
        }
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        text.forEach { c ->
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
