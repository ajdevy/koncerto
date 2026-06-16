package com.flexsentlabs.koncerto.app

import com.flexsentlabs.koncerto.core.config.ServiceConfig
import com.flexsentlabs.koncerto.core.config.WorkflowDefinition
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.core.result.runCatchingResult
import com.flexsentlabs.koncerto.workflow.FrontMatterParser
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

class ConfigService(
    private val workflowPath: String
) {
    fun loadConfig(): Result<WorkflowDefinition, IllegalStateException> = runCatchingResult {
        val path = Paths.get(workflowPath)
        if (!Files.exists(path)) {
            throw IllegalStateException("Workflow file not found: $workflowPath")
        }
        val content = Files.readString(path)
        FrontMatterParser.parse(content)
    }

    fun loadServiceConfig(): Result<ServiceConfig, IllegalStateException> = loadConfig().map { def ->
        val workflowFileDir = Paths.get(workflowPath).parent?.toString() ?: "."
        ServiceConfig.fromMap(def.config, workflowFileDir)
    }

    fun validateConfig(configMap: Map<String, Any?>): Result<Unit, IllegalStateException> {
        val workflowFileDir = Paths.get(workflowPath).parent?.toString() ?: "."
        return runCatchingResult<Unit, IllegalStateException> {
            ServiceConfig.fromMap(configMap, workflowFileDir)
            Unit
        }
    }

    fun saveConfig(configMap: Map<String, Any?>): Result<Unit, IllegalStateException> {
        val validation = validateConfig(configMap)
        if (validation is Result.Failure) {
            return validation
        }
        return runCatchingResult {
            val path = Paths.get(workflowPath)
            val currentDef = loadConfig().getOrNull()
            val promptTemplate = currentDef?.promptTemplate ?: ""

            val yaml = Yaml(dumperOptions())
            val writer = StringWriter()
            yaml.dump(configMap, writer)
            val yamlText = writer.toString().trim()

            val fullContent = "---\n$yamlText\n---\n\n$promptTemplate"
            Files.writeString(path, fullContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        }
    }

    fun saveRawYaml(yamlText: String): Result<Unit, IllegalStateException> {
        val parsed: Any? = try {
            Yaml().load(yamlText)
        } catch (e: Exception) {
            return Result.Failure(IllegalStateException("Invalid YAML: ${e.message}", e))
        }
        if (parsed == null) {
            return Result.Failure(IllegalStateException("Empty YAML"))
        }
        if (parsed !is Map<*, *>) {
            return Result.Failure(IllegalStateException("YAML root must be a map"))
        }
        @Suppress("UNCHECKED_CAST")
        val configMap = parsed as Map<String, Any?>
        return saveConfig(configMap)
    }

    private fun dumperOptions(): DumperOptions {
        val options = DumperOptions()
        options.defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        options.indent = 2
        options.width = 120
        return options
    }

    fun getWorkflowPath(): String = workflowPath
}
