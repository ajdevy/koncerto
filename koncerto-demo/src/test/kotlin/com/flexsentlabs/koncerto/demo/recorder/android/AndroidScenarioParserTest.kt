package com.flexsentlabs.koncerto.demo.recorder.android

import org.junit.jupiter.api.Test

class AndroidScenarioParserTest {

    private val minimal = """
        android_scenario:
          appId: com.example.app
          description: "Sign in"
          steps:
            - action: launch
            - action: tap
              text: "Sign in"
    """.trimIndent()

    @Test
    fun `parses a minimal scenario`() {
        val scenario = AndroidScenarioParser.parse(minimal)!!

        assert(scenario.appId == "com.example.app")
        assert(scenario.description == "Sign in")
        assert(scenario.steps.size == 2)
        assert((scenario.steps[0] as AndroidStep.Launch).clearState)
        assert((scenario.steps[1] as AndroidStep.Tap).text == "Sign in")
    }

    @Test
    fun `tolerates fences and indentation`() {
        assert(AndroidScenarioParser.parse("Here you go:\n\n```yaml\n$minimal\n```") != null)
        assert(AndroidScenarioParser.parse(minimal.lines().joinToString("\n") { "    $it" }) != null)
    }

    @Test
    fun `rejects a scenario with no appId`() {
        // Without it Maestro cannot even start, so this is caught before a device is touched.
        assert(AndroidScenarioParser.parse("android_scenario:\n  steps:\n    - action: launch\n") == null)
        assert(AndroidScenarioParser.parse("android_scenario:\n  appId: \"  \"\n  steps:\n    - action: launch\n") == null)
    }

    @Test
    fun `rejects malformed, empty and foreign documents`() {
        assert(AndroidScenarioParser.parse("") == null)
        assert(AndroidScenarioParser.parse("api_scenario:\n  steps: []") == null)
        assert(AndroidScenarioParser.parse("android_scenario: not-a-map") == null)
        assert(AndroidScenarioParser.parse("android_scenario:\n  appId: x") == null)
        assert(AndroidScenarioParser.parse("android_scenario:\n  appId: x\n  steps:\n    - [unclosed") == null)
    }

    @Test
    fun `rejects a scenario that only waits`() {
        assert(
            AndroidScenarioParser.parse(
                "android_scenario:\n  appId: com.x\n  steps:\n    - action: wait\n      ms: 500\n"
            ) == null
        )
    }

    @Test
    fun `parses every action type`() {
        val scenario = AndroidScenarioParser.parse(
            """
            android_scenario:
              appId: com.example.app
              steps:
                - action: launch
                  clearState: false
                - action: tap
                  id: "send-button"
                  note: "send it"
                - action: input
                  value: "user@example.com"
                  into: "Email"
                - action: assert_visible
                  text: "Welcome"
                - action: scroll
                  direction: UP
                - action: back
                - action: wait
                  ms: 1500
                - action: screenshot
                  name: "done"
            """.trimIndent()
        )!!

        assert(!(scenario.steps[0] as AndroidStep.Launch).clearState)
        assert((scenario.steps[1] as AndroidStep.Tap).id == "send-button")
        assert((scenario.steps[1] as AndroidStep.Tap).note == "send it")
        assert((scenario.steps[2] as AndroidStep.Input).into == "Email")
        assert((scenario.steps[3] as AndroidStep.AssertVisible).text == "Welcome")
        assert((scenario.steps[4] as AndroidStep.Scroll).direction == "up")
        assert(scenario.steps[5] is AndroidStep.Back)
        assert((scenario.steps[6] as AndroidStep.Wait).ms == 1500)
        assert((scenario.steps[7] as AndroidStep.Screenshot).name == "done")
        assert(scenario.assertionCount == 1)
    }

    @Test
    fun `drops steps that cannot be executed`() {
        val scenario = AndroidScenarioParser.parse(
            """
            android_scenario:
              appId: com.example.app
              steps:
                - action: tap
                - action: input
                - action: assert_visible
                - action: teleport
                  text: x
                - "a bare string"
                - action: tap
                  text: "OK"
            """.trimIndent()
        )!!

        // A tap with no selector, an input with no value and an assert with no target are all
        // unexecutable; only the well-formed tap survives.
        assert(scenario.steps.size == 1)
        assert((scenario.steps.single() as AndroidStep.Tap).text == "OK")
    }

    @Test
    fun `falls back on defaults for optional fields`() {
        val scenario = AndroidScenarioParser.parse(
            """
            android_scenario:
              appId: com.example.app
              steps:
                - action: scroll
                  direction: sideways
                - action: wait
                - action: screenshot
                - action: tap
                  text: "OK"
            """.trimIndent()
        )!!

        assert((scenario.steps[0] as AndroidStep.Scroll).direction == "down")
        assert((scenario.steps[1] as AndroidStep.Wait).ms == 1000)
        assert((scenario.steps[2] as AndroidStep.Screenshot).name == "screenshot")
    }

    @Test
    fun `reads a numeric wait written as a string`() {
        val scenario = AndroidScenarioParser.parse(
            "android_scenario:\n  appId: com.x\n  steps:\n    - action: wait\n      ms: \"750\"\n    - action: back\n"
        )!!

        assert((scenario.steps[0] as AndroidStep.Wait).ms == 750)
    }

    @Test
    fun `extractBlock anchors on the root key`() {
        assert(AndroidScenarioParser.extractBlock("prose\nandroid_scenario:\n  appId: x") == "android_scenario:\n  appId: x")
        assert(AndroidScenarioParser.extractBlock("nothing here") == null)
    }
}
