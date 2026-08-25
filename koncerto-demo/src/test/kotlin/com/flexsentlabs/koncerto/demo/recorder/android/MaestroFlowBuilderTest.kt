package com.flexsentlabs.koncerto.demo.recorder.android

import org.junit.jupiter.api.Test

class MaestroFlowBuilderTest {

    private fun flow(vararg steps: AndroidStep, appId: String = "com.example.app", description: String = "Demo") =
        MaestroFlowBuilder.build(AndroidScenario(appId, description, steps.toList()))

    @Test
    fun `emits the appId header and document separator Maestro requires`() {
        val yaml = flow(AndroidStep.Back())

        assert(yaml.startsWith("# Demo\n"))
        assert(yaml.contains("appId: com.example.app"))
        assert(yaml.contains("\n---\n"))
        assert(yaml.indexOf("appId:") < yaml.indexOf("---"))
    }

    @Test
    fun `falls back to a generic title when the scenario has no description`() {
        assert(flow(AndroidStep.Back(), description = "").startsWith("# Koncerto demo"))
    }

    @Test
    fun `launch clears state by default and can be told not to`() {
        assert(flow(AndroidStep.Launch()).contains("- launchApp:\n    clearState: true"))
        assert(flow(AndroidStep.Launch(clearState = false)).contains("- launchApp"))
        assert(!flow(AndroidStep.Launch(clearState = false)).contains("clearState"))
    }

    @Test
    fun `a resource id is preferred over visible text`() {
        // Ids survive copy changes; text does not.
        val yaml = flow(AndroidStep.Tap(text = "Send", id = "send-button"))

        assert(yaml.contains("- tapOn:\n    id: \"send-button\""))
        assert(!yaml.contains("text: \"Send\""))
    }

    @Test
    fun `tap and assert fall back to text when no id is given`() {
        assert(flow(AndroidStep.Tap(text = "Send")).contains("- tapOn:\n    text: \"Send\""))
        assert(flow(AndroidStep.AssertVisible(text = "Welcome")).contains("- assertVisible:\n    text: \"Welcome\""))
        assert(flow(AndroidStep.AssertVisible(id = "banner")).contains("- assertVisible:\n    id: \"banner\""))
    }

    @Test
    fun `input taps the target field first when one is named`() {
        val yaml = flow(AndroidStep.Input("user@example.com", into = "Email"))

        assert(yaml.contains("- tapOn:\n    text: \"Email\""))
        assert(yaml.indexOf("tapOn") < yaml.indexOf("inputText"))
        assert(yaml.contains("- inputText: \"user@example.com\""))
    }

    @Test
    fun `input without a target types into the focused field`() {
        val yaml = flow(AndroidStep.Input("hello"))

        assert(yaml.contains("- inputText: \"hello\""))
        assert(!yaml.contains("tapOn"))
    }

    @Test
    fun `scrolling down is Maestro's argument-less scroll`() {
        // `scroll: {direction: ...}` is rejected by the CLI as an unknown property.
        assert(flow(AndroidStep.Scroll("down")).contains("- scroll\n"))
        assert(!flow(AndroidStep.Scroll("down")).contains("direction"))
    }

    @Test
    fun `other directions become an inverted swipe`() {
        // The scenario names where the content moves; a swipe names what the finger does.
        assert(flow(AndroidStep.Scroll("up")).contains("- swipe:\n    direction: DOWN"))
        assert(flow(AndroidStep.Scroll("left")).contains("- swipe:\n    direction: RIGHT"))
        assert(flow(AndroidStep.Scroll("right")).contains("- swipe:\n    direction: LEFT"))
    }

    @Test
    fun `waits are capped so a scenario cannot stall the recording`() {
        assert(flow(AndroidStep.Wait(1500)).contains("timeout: 1500"))
        assert(flow(AndroidStep.Wait(99_000)).contains("timeout: 10000"))
        assert(flow(AndroidStep.Wait(-5)).contains("timeout: 0"))
    }

    @Test
    fun `back and screenshot render as their Maestro commands`() {
        assert(flow(AndroidStep.Back()).contains("- back"))
        assert(flow(AndroidStep.Screenshot("after-submit")).contains("- takeScreenshot: \"after-submit\""))
    }

    @Test
    fun `notes are carried into the flow as comments`() {
        val yaml = flow(AndroidStep.Tap(text = "OK", note = "confirm the dialog"))

        assert(yaml.contains("# confirm the dialog"))
        assert(yaml.indexOf("# confirm the dialog") < yaml.indexOf("- tapOn"))
    }

    @Test
    fun `quoting neutralises characters that would reshape the YAML`() {
        assert(MaestroFlowBuilder.quote("""say "hi"""") == """"say \"hi\""""")
        assert(MaestroFlowBuilder.quote("""back\slash""") == """"back\\slash"""")
        assert(MaestroFlowBuilder.quote("a: b") == "\"a: b\"")
        assert(MaestroFlowBuilder.quote("two\nlines") == "\"two lines\"")
    }

    @Test
    fun `a multi-line note cannot break out of its comment`() {
        val yaml = flow(AndroidStep.Back(note = "first\n- tapOn: injected"))

        assert(yaml.contains("# first - tapOn: injected"))
        assert(yaml.lines().count { it.trimStart().startsWith("- ") } == 1)
    }

    @Test
    fun `steps keep their authored order`() {
        val yaml = flow(AndroidStep.Launch(), AndroidStep.Tap(text = "A"), AndroidStep.AssertVisible(text = "B"))

        assert(yaml.indexOf("launchApp") < yaml.indexOf("\"A\""))
        assert(yaml.indexOf("\"A\"") < yaml.indexOf("\"B\""))
    }
}
