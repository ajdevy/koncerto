package com.flexsentlabs.koncerto.core.review

/**
 * Minimal, dependency-free glob matcher for path patterns used by review routing.
 * Supports `**` (any path segments, including `/`), `*` (any chars except `/`), and `?`.
 * Matching is done against forward-slash paths relative to the repo root.
 */
object Glob {

    // Shared across orchestrator coroutines — must be safe for concurrent routing decisions.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    fun matches(pattern: String, path: String): Boolean {
        val normalizedPath = path.trimStart('/')
        val regex = cache.getOrPut(pattern) { Regex(translate(pattern)) }
        return regex.matches(normalizedPath)
    }

    fun matchesAny(patterns: Collection<String>, path: String): Boolean =
        patterns.any { matches(it, path) }

    private fun translate(glob: String): String {
        val g = glob.trim().trimStart('/')
        val sb = StringBuilder("^")
        var i = 0
        while (i < g.length) {
            val c = g[i]
            when (c) {
                '*' -> {
                    val doubleStar = i + 1 < g.length && g[i + 1] == '*'
                    if (doubleStar) {
                        // `**/` matches zero or more path segments; bare `**` matches anything.
                        val slashAfter = i + 2 < g.length && g[i + 2] == '/'
                        if (slashAfter) {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                        continue
                    } else {
                        sb.append("[^/]*")
                    }
                }
                '?' -> sb.append("[^/]")
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return sb.toString()
    }
}
