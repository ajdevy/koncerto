package com.anomaly.koncerto.orchestrator

import com.anomaly.koncerto.core.model.Issue
import java.time.Instant

object FollowUpRenderer {
    fun render(template: String, issue: Issue): String {
        var result = template
            .replace("{{ issue.id }}", issue.id)
            .replace("{{ issue.identifier }}", issue.identifier)
            .replace("{{ issue.title }}", issue.title)
            .replace("{{ issue.url }}", issue.url ?: "")
            .replace("{{ issue.state }}", issue.state)
            .replace("{{ issue.labels }}", issue.labels.joinToString(", "))
            .replace("{{ now }}", Instant.now().toString())
        return result
    }
}
