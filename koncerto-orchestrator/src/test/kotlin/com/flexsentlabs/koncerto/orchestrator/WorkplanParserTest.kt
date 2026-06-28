package com.flexsentlabs.koncerto.orchestrator

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.config.SubtaskManifest
import com.flexsentlabs.koncerto.core.config.SubtaskDef
import com.flexsentlabs.koncerto.core.result.Result
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WorkplanParserTest {

    private val parser = WorkplanParser()

    @Test
    fun `parse valid workplan`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskDef(id = "step-1", description = "Step 1", prompt = "Do A", dependsOn = emptyList()),
                SubtaskDef(id = "step-2", description = "Step 2", prompt = "Do B", dependsOn = listOf("step-1"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Success::class)
        val parsed = (result as Result.Success).value
        assertThat(parsed.subtasks.size).isEqualTo(2)
    }

    @Test
    fun `return NOT_FOUND when no workplan exists`(@TempDir tempDir: Path) {
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on circular dependencies`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "Do A", dependsOn = listOf("b")),
                SubtaskDef(id = "b", description = "B", prompt = "Do B", dependsOn = listOf("a"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on duplicate subtask IDs`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskDef(id = "same", description = "A", prompt = "Do A", dependsOn = emptyList()),
                SubtaskDef(id = "same", description = "B", prompt = "Do B", dependsOn = emptyList())
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    @Test
    fun `fail on empty subtasks list`(@TempDir tempDir: Path) {
        val workplanDir = tempDir.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        Files.writeString(workplanDir.resolve("workplan.json"), """{"issueId":"KONC-123","subtasks":[]}""")
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
        val error = (result as Result.Failure).error
        assertThat(error).isInstanceOf(ParseError.INVALID::class)
        assertThat((error as ParseError.INVALID).message).isEqualTo("subtasks list is empty")
    }

    @Test
    fun `fail on malformed JSON`(@TempDir tempDir: Path) {
        val workplanDir = tempDir.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        Files.writeString(workplanDir.resolve("workplan.json"), "{not valid json")
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
        assertThat((result as Result.Failure).error).isInstanceOf(ParseError.INVALID::class)
    }

    @Test
    fun `fail on dependsOn referencing nonexistent ID`(@TempDir tempDir: Path) {
        val manifest = SubtaskManifest(
            issueId = "KONC-123",
            subtasks = listOf(
                SubtaskDef(id = "a", description = "A", prompt = "Do A", dependsOn = listOf("nonexistent"))
            )
        )
        writeWorkplan(tempDir, manifest)
        val result = parser.parse(tempDir)
        assertThat(result).isInstanceOf(Result.Failure::class)
    }

    private fun writeWorkplan(dir: Path, manifest: SubtaskManifest) {
        val json = Json { prettyPrint = true }
        val workplanDir = dir.resolve("_koncerto")
        Files.createDirectories(workplanDir)
        Files.writeString(workplanDir.resolve("workplan.json"), json.encodeToString(manifest))
    }
}