package com.flexsentlabs.koncerto.demo.recorder.api

import org.junit.jupiter.api.Test

class OpenApiInventoryTest {

    private val spec = """
        {
          "info": {"title": "Items API"},
          "components": {
            "schemas": {
              "Item": {"properties": {"name": {"type": "string"}, "quantity": {"type": "integer"}}}
            }
          },
          "paths": {
            "/api/items": {
              "get": {
                "summary": "List items",
                "parameters": [{"name": "limit"}, {"name": "offset"}],
                "responses": {"200": {}, "500": {}}
              },
              "post": {
                "summary": "Create an item",
                "requestBody": {
                  "content": {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Item"}}}
                },
                "responses": {"201": {}}
              }
            },
            "/api/items/{id}": {
              "get": {"summary": "Read one", "responses": {"200": {}, "404": {}}},
              "servers": {"ignored": true}
            }
          }
        }
    """.trimIndent()

    @Test
    fun `compact keeps only what is needed to author a request`() {
        val inventory = OpenApiInventory.compact(spec)!!

        assert(inventory.contains("\"title\":\"Items API\""))
        assert(inventory.contains("\"route_count\":3"))
        assert(inventory.contains("\"method\":\"GET\",\"path\":\"/api/items\""))
        assert(inventory.contains("\"summary\":\"List items\""))
        assert(inventory.contains("\"params\":[\"limit\",\"offset\"]"))
        assert(inventory.contains("\"statuses\":[\"200\",\"500\"]"))
        assert(!inventory.contains("\"type\""))
    }

    @Test
    fun `compact resolves a request body ref one hop into components`() {
        val inventory = OpenApiInventory.compact(spec)!!

        assert(inventory.contains("\"body\":[\"name\",\"quantity\"]"))
    }

    @Test
    fun `compact reads an inline request body schema`() {
        val inventory = OpenApiInventory.compact(
            """
            {"paths": {"/x": {"post": {"requestBody": {"content":
              {"application/json": {"schema": {"properties": {"a": {}, "b": {}}}}}}}}}}
            """.trimIndent()
        )!!

        assert(inventory.contains("\"body\":[\"a\",\"b\"]"))
    }

    @Test
    fun `compact flags operations that declare security`() {
        val inventory = OpenApiInventory.compact(
            """{"paths": {"/private": {"get": {"security": [{"bearer": []}]}}}}"""
        )!!

        assert(inventory.contains("\"auth\":true"))
    }

    @Test
    fun `compact skips non-method keys and unresolvable refs`() {
        val inventory = OpenApiInventory.compact(spec)!!

        assert(!inventory.contains("servers"))

        val danglingRef = OpenApiInventory.compact(
            """
            {"paths": {"/x": {"post": {"requestBody": {"content":
              {"application/json": {"schema": {"${'$'}ref": "#/components/schemas/Missing"}}}}}}}}
            """.trimIndent()
        )!!
        assert(!danglingRef.contains("\"body\""))
    }

    @Test
    fun `compact tolerates a body with no json content type or no schema`() {
        val formOnly = OpenApiInventory.compact(
            """{"paths": {"/x": {"post": {"requestBody": {"content": {"text/plain": {}}}}}}}"""
        )!!
        assert(!formOnly.contains("\"body\""))

        val noBody = OpenApiInventory.compact("""{"paths": {"/x": {"post": {}}}}""")!!
        assert(!noBody.contains("\"body\""))
    }

    @Test
    fun `compact rejects documents that are not usable specs`() {
        assert(OpenApiInventory.compact("not json") == null)
        assert(OpenApiInventory.compact("{}") == null)
        assert(OpenApiInventory.compact("""{"paths": {}}""") == null)
        assert(OpenApiInventory.compact("""{"paths": {"/x": {"note": "no methods"}}}""") == null)
    }

    @Test
    fun `compact falls back to an empty title when info is absent`() {
        val inventory = OpenApiInventory.compact("""{"paths": {"/x": {"get": {}}}}""")!!

        assert(inventory.contains("\"title\":\"\""))
    }

    @Test
    fun `discoveryScenario walks parameterless public GETs only`() {
        val scenario = OpenApiInventory.discoveryScenario(spec)!!

        assert(scenario.description == "Read-only walk through Items API")
        assert(scenario.requestCount == 1)
        val request = scenario.steps.filterIsInstance<ApiStep.Request>().single()
        assert(request.method == "GET")
        assert(request.path == "/api/items")
        assert(request.expectStatus == 200)
        assert(request.note == "List items")
        assert(request.headers == mapOf("Accept" to "application/json"))
        assert((scenario.steps.first() as ApiStep.Note).note.startsWith("No authored scenario"))
    }

    @Test
    fun `discoveryScenario prefers the documented success status`() {
        val scenario = OpenApiInventory.discoveryScenario(
            """{"paths": {"/x": {"get": {"responses": {"404": {}, "204": {}}}}}}"""
        )!!

        assert(scenario.steps.filterIsInstance<ApiStep.Request>().single().expectStatus == 204)
    }

    @Test
    fun `discoveryScenario defaults the status and note when the spec documents neither`() {
        val request = OpenApiInventory.discoveryScenario("""{"paths": {"/x": {"get": {}}}}""")!!
            .steps.filterIsInstance<ApiStep.Request>().single()

        assert(request.expectStatus == 200)
        assert(request.note == "GET /x")
    }

    @Test
    fun `discoveryScenario skips authenticated routes and honours its cap`() {
        val secured = OpenApiInventory.discoveryScenario(
            """{"paths": {"/private": {"get": {"security": [{"bearer": []}]}}}}"""
        )
        assert(secured == null)

        val many = (1..10).joinToString(",") { """"/r$it": {"get": {}}""" }
        val capped = OpenApiInventory.discoveryScenario("""{"paths": {$many}}""", maxRoutes = 3)!!
        assert(capped.requestCount == 3)
    }

    @Test
    fun `discoveryScenario names the service generically when the spec has no title`() {
        val scenario = OpenApiInventory.discoveryScenario("""{"info": {"title": " "}, "paths": {"/x": {"get": {}}}}""")!!

        assert(scenario.description == "Read-only walk through the deployed service")
    }

    @Test
    fun `discoveryScenario returns nothing when there is no safe route to walk`() {
        assert(OpenApiInventory.discoveryScenario("nonsense") == null)
        assert(OpenApiInventory.discoveryScenario("{}") == null)
        assert(OpenApiInventory.discoveryScenario("""{"paths": {"/x/{id}": {"get": {}}}}""") == null)
        assert(OpenApiInventory.discoveryScenario("""{"paths": {"/x": {"post": {}}}}""") == null)
    }

    @Test
    fun `the candidate spec paths lead with the springdoc and openapi defaults`() {
        assert(OpenApiInventory.CANDIDATE_PATHS.first() == "/v3/api-docs")
        assert(OpenApiInventory.CANDIDATE_PATHS.contains("/openapi.json"))
        assert(OpenApiInventory.CANDIDATE_PATHS.contains("/swagger.json"))
    }
}
