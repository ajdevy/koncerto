package com.flexsentlabs.koncerto.demo.recorder.api

import org.junit.jupiter.api.Test

class ApiScenarioParserTest {

    private val minimal = """
        api_scenario:
          description: "Items API"
          steps:
            - action: request
              method: get
              path: /api/items
    """.trimIndent()

    @Test
    fun `parses a minimal scenario`() {
        val scenario = ApiScenarioParser.parse(minimal)!!

        assert(scenario.description == "Items API")
        assert(scenario.requestCount == 1)
        val request = scenario.steps.single() as ApiStep.Request
        assert(request.method == "GET")
        assert(request.path == "/api/items")
    }

    @Test
    fun `tolerates markdown fences and leading prose`() {
        val raw = "Here is the scenario you asked for.\n\n```yaml\n" + minimal + "\n```"

        assert(ApiScenarioParser.parse(raw)?.requestCount == 1)
    }

    @Test
    fun `tolerates a block indented to sit inside a fence`() {
        val raw = minimal.lines().joinToString("\n") { "    " + it }

        assert(ApiScenarioParser.parse(raw)?.requestCount == 1)
    }

    @Test
    fun `parses every field of a request step`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              description: Full
              steps:
                - action: request
                  method: post
                  path: api/items
                  note: Create it
                  headers:
                    Content-Type: application/json
                    X-Count: 3
                  body:
                    name: Demo
                    quantity: 2
                  expect_status: 201
                  expect_contains: Demo
                  save:
                    item_id: id
            """.trimIndent()
        )!!

        val request = scenario.steps.single() as ApiStep.Request
        assert(request.method == "POST")
        assert(request.path == "api/items")
        assert(request.note == "Create it")
        assert(request.headers == mapOf("Content-Type" to "application/json", "X-Count" to "3"))
        assert(request.body == """{"name":"Demo","quantity":2}""")
        assert(request.expectStatus == 201)
        assert(request.expectContains == "Demo")
        assert(request.save == mapOf("item_id" to "id"))
    }

    @Test
    fun `encodes a list body as JSON and keeps a string body verbatim`() {
        val listBody = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  path: /bulk
                  body:
                    - 1
                    - 2
            """.trimIndent()
        )!!.steps.single() as ApiStep.Request
        assert(listBody.body == "[1,2]")

        val stringBody = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  path: /raw
                  body: "already json"
            """.trimIndent()
        )!!.steps.single() as ApiStep.Request
        assert(stringBody.body == "already json")
    }

    @Test
    fun `blank and non-scalar bodies degrade to null`() {
        val blank = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  path: /a
                  body: "   "
            """.trimIndent()
        )!!.steps.single() as ApiStep.Request
        assert(blank.body == null)

        val numeric = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  path: /a
                  body: 42
            """.trimIndent()
        )!!.steps.single() as ApiStep.Request
        assert(numeric.body == "42")
    }

    @Test
    fun `parses note and wait steps including the default wait`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: note
                  note: Section one
                - action: wait
                  ms: 500
                  note: pausing
                - action: wait
                - action: request
                  path: /x
            """.trimIndent()
        )!!

        assert((scenario.steps[0] as ApiStep.Note).note == "Section one")
        assert((scenario.steps[1] as ApiStep.Wait).ms == 500)
        assert((scenario.steps[1] as ApiStep.Wait).note == "pausing")
        assert((scenario.steps[2] as ApiStep.Wait).ms == 1000)
    }

    @Test
    fun `infers the action when the model omits it`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - note: just narration
                - ms: 250
                - path: /inferred
                - unrelated: value
            """.trimIndent()
        )!!

        assert(scenario.steps.size == 3)
        assert(scenario.steps[0] is ApiStep.Note)
        assert(scenario.steps[1] is ApiStep.Wait)
        assert((scenario.steps[2] as ApiStep.Request).method == "GET")
    }

    @Test
    fun `reads a numeric ms written as a string and a quoted expect_status`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: wait
                  ms: "750"
                - action: request
                  path: /x
                  expect_status: "404"
            """.trimIndent()
        )!!

        assert((scenario.steps[0] as ApiStep.Wait).ms == 750)
        assert((scenario.steps[1] as ApiStep.Request).expectStatus == 404)
    }

    @Test
    fun `rejects scenarios with no executable request`() {
        assert(
            ApiScenarioParser.parse(
                """
                api_scenario:
                  steps:
                    - action: note
                      note: only narration
                """.trimIndent()
            ) == null
        )
    }

    @Test
    fun `rejects malformed, empty and foreign documents`() {
        assert(ApiScenarioParser.parse("") == null)
        assert(ApiScenarioParser.parse("demo_scenario:\n  steps: []") == null)
        assert(ApiScenarioParser.parse("api_scenario: not-a-map") == null)
        assert(ApiScenarioParser.parse("api_scenario:\n  description: x") == null)
        assert(ApiScenarioParser.parse("api_scenario:\n  steps: \"nope\"") == null)
        assert(ApiScenarioParser.parse("api_scenario:\n  steps:\n    - [unclosed") == null)
    }

    @Test
    fun `rejects a request step without a path`() {
        assert(
            ApiScenarioParser.parse(
                """
                api_scenario:
                  steps:
                    - action: request
                      method: GET
                """.trimIndent()
            ) == null
        )
    }

    @Test
    fun `drops steps that are not mappings and unknown actions`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - "a bare string"
                - action: teleport
                  path: /nope
                - action: request
                  path: /ok
            """.trimIndent()
        )!!

        assert(scenario.steps.size == 1)
        assert((scenario.steps.single() as ApiStep.Request).path == "/ok")
    }

    @Test
    fun `ignores blank keys and empty method or path values`() {
        val scenario = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  method: "  "
                  path: /fallback
                  headers:
                    "  ": ignored
                    Accept: application/json
                  save:
                    "": nope
            """.trimIndent()
        )!!

        val request = scenario.steps.single() as ApiStep.Request
        assert(request.method == "GET")
        assert(request.headers == mapOf("Accept" to "application/json"))
        assert(request.save.isEmpty())
    }

    @Test
    fun `header values that are absent become empty strings`() {
        val request = ApiScenarioParser.parse(
            """
            api_scenario:
              steps:
                - action: request
                  path: /x
                  headers:
                    X-Empty:
            """.trimIndent()
        )!!.steps.single() as ApiStep.Request

        assert(request.headers == mapOf("X-Empty" to ""))
    }

    @Test
    fun `extractBlock keeps the document from the root key onward`() {
        val extracted = ApiScenarioParser.extractBlock("prose\n```yaml\napi_scenario:\n  steps: []\n```")

        assert(extracted == "api_scenario:\n  steps: []")
        assert(ApiScenarioParser.extractBlock("no root key here") == null)
        assert(ApiScenarioParser.extractBlock("api_scenario:") == "api_scenario:")
    }
}
