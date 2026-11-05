package com.flexsentlabs.koncerto.demo.recorder.api

import java.nio.file.Files
import org.junit.jupiter.api.Test

class DemoScenarioFilesTest {

    private val dir = Files.createTempDirectory("scenario-files").toFile().also { it.deleteOnExit() }

    @Test
    fun `names each staging file by kind`() {
        assert(DemoScenarioFiles.browserScenario("/tmp", "DEMO-1").name == "DEMO-1-scenario.yaml")
        assert(DemoScenarioFiles.apiScenario("/tmp", "DEMO-1").name == "DEMO-1-api-scenario.yaml")
        assert(DemoScenarioFiles.credentials("/tmp", "DEMO-1").name == "DEMO-1-credentials.env")
        assert(DemoScenarioFiles.androidScenario("/tmp", "DEMO-1").name == "DEMO-1-android-scenario.yaml")
        assert(DemoScenarioFiles.apiScenario("/tmp", "DEMO-1").parentFile.name == "tmp")
    }

    @Test
    fun `an api scenario is not picked up by a browser lookup`() {
        DemoScenarioFiles.apiScenario(dir.path, "only-api").writeText("api_scenario:")

        assert(DemoScenarioFiles.findExisting(dir.path, listOf("only-api"), DemoScenarioFiles.BROWSER_SUFFIX) == null)
        assert(DemoScenarioFiles.findExisting(dir.path, listOf("only-api"), DemoScenarioFiles.API_SUFFIX) != null)
    }

    @Test
    fun `findExisting returns the first key that has a staged file`() {
        DemoScenarioFiles.apiScenario(dir.path, "ident").writeText("api_scenario:")

        val found = DemoScenarioFiles.findExisting(dir.path, listOf("uuid", "ident"), DemoScenarioFiles.API_SUFFIX)

        assert(found?.name == "ident-api-scenario.yaml")
    }

    @Test
    fun `findExisting prefers the earlier key when both are staged`() {
        DemoScenarioFiles.browserScenario(dir.path, "first").writeText("a")
        DemoScenarioFiles.browserScenario(dir.path, "second").writeText("b")

        val found = DemoScenarioFiles.findExisting(
            dir.path, listOf("first", "second"), DemoScenarioFiles.BROWSER_SUFFIX
        )

        assert(found?.name == "first-scenario.yaml")
    }

    @Test
    fun `findExisting skips blank keys and returns null when nothing is staged`() {
        assert(DemoScenarioFiles.findExisting(dir.path, listOf("", "  "), DemoScenarioFiles.API_SUFFIX) == null)
        assert(DemoScenarioFiles.findExisting(dir.path, emptyList(), DemoScenarioFiles.API_SUFFIX) == null)
        assert(DemoScenarioFiles.findExisting(dir.path, listOf("absent"), DemoScenarioFiles.CREDENTIALS_SUFFIX) == null)
    }

    @Test
    fun `findExisting ignores a directory that happens to match the name`() {
        DemoScenarioFiles.apiScenario(dir.path, "dirlike").mkdirs()

        assert(DemoScenarioFiles.findExisting(dir.path, listOf("dirlike"), DemoScenarioFiles.API_SUFFIX) == null)
    }
}
