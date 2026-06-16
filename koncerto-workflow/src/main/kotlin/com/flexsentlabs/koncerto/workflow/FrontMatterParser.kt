package com.flexsentlabs.koncerto.workflow

import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

object FrontMatterParser {

    private const val DELIMITER = "---"

    fun parse(content: String): WorkflowDefinition {
        val normalized = content.replace("\r\n", "\n")
        if (!normalized.startsWith(DELIMITER)) {
            return WorkflowDefinition(emptyMap(), normalized.trim())
        }
        val lines = normalized.lines()
        val closingIndex = lines.drop(1).indexOfFirst { it.trim() == DELIMITER }
        if (closingIndex < 0) {
            throw IllegalStateException("workflow_parse_error: opening --- without closing ---")
        }
        val yamlLines = lines.subList(1, closingIndex + 1)
        val bodyLines = lines.subList(closingIndex + 2, lines.size)
        val yamlText = yamlLines.joinToString("\n")
        
        val parsed: Any?
        try {
            parsed = Yaml().load(yamlText)
        } catch (e: YAMLException) {
            throw IllegalStateException("workflow_front_matter_not_a_map", e)
        }
        
        if (parsed == null) {
            throw IllegalStateException("workflow_front_matter_not_a_map: empty YAML")
        }
        if (parsed !is Map<*, *>) {
            throw IllegalStateException("workflow_front_matter_not_a_map")
        }
        @Suppress("UNCHECKED_CAST")
        val map = parsed as Map<String, Any?>
        return WorkflowDefinition(map, bodyLines.joinToString("\n").trim())
    }
}