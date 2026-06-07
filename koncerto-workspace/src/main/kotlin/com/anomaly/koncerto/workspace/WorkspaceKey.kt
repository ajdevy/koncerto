package com.anomaly.koncerto.workspace

object WorkspaceKey {
    private val SAFE = Regex("[A-Za-z0-9._-]")

    fun sanitize(identifier: String): String {
        if (identifier.isEmpty()) return "_"
        val sb = StringBuilder()
        for (c in identifier) sb.append(if (SAFE.matches(c.toString())) c else '_')
        return sb.toString()
    }
}