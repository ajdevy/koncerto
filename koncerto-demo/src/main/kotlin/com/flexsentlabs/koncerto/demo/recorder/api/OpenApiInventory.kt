package com.flexsentlabs.koncerto.demo.recorder.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Compacts a live OpenAPI document into the small route inventory that grounds API demo-scenario
 * generation — the server-side counterpart of the DOM inventory the browser path already uses.
 *
 * Without this the model invents endpoints; with it, it writes requests against paths, parameters
 * and status codes the deployed service actually declares. Specs run to hundreds of kilobytes, so
 * everything not needed to author a request is dropped.
 */
object OpenApiInventory {

    /** Paths a service is likely to publish its spec on, in the order they are probed. */
    val CANDIDATE_PATHS: List<String> = listOf(
        "/v3/api-docs",
        "/openapi.json",
        "/swagger.json",
        "/api-docs",
        "/api/openapi.json",
        "/v2/api-docs",
        "/docs/openapi.json"
    )

    private const val MAX_ROUTES = 60
    private const val MAX_BODY_FIELDS = 12
    private const val DISCOVERY_ROUTES = 6
    private val METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Returns a compact JSON inventory, or null when [spec] is not a usable OpenAPI document. */
    fun compact(spec: String): String? {
        val root = runCatching { JSON.parseToJsonElement(spec).jsonObject }.getOrNull() ?: return null
        val paths = root["paths"] as? JsonObject ?: return null
        val components = root["components"] as? JsonObject

        val routes = paths.entries.asSequence()
            .flatMap { (path, node) ->
                val operations = node as? JsonObject ?: return@flatMap emptySequence()
                operations.entries.asSequence()
                    .filter { it.key.lowercase() in METHODS }
                    .map { (method, opNode) ->
                        renderRoute(method, path, opNode as? JsonObject ?: JsonObject(emptyMap()), components)
                    }
            }
            .take(MAX_ROUTES)
            .toList()

        if (routes.isEmpty()) return null
        val title = ((root["info"] as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return buildString {
            append("{\"title\":")
            append(JsonEncoding.encode(title))
            append(",\"route_count\":")
            append(routes.size)
            append(",\"routes\":[")
            append(routes.joinToString(","))
            append("]}")
        }
    }

    private fun renderRoute(
        method: String,
        path: String,
        operation: JsonObject,
        components: JsonObject?
    ): String {
        val fields = linkedMapOf<String, Any?>(
            "method" to method.uppercase(),
            "path" to path
        )
        text(operation, "summary")?.let { fields["summary"] = it }
        parameterNames(operation).takeIf { it.isNotEmpty() }?.let { fields["params"] = it }
        requestBodyFields(operation, components).takeIf { it.isNotEmpty() }?.let { fields["body"] = it }
        statusCodes(operation).takeIf { it.isNotEmpty() }?.let { fields["statuses"] = it }
        if (requiresAuth(operation)) fields["auth"] = true
        return JsonEncoding.encode(fields)
    }

    /**
     * Builds a read-only scenario straight from the spec, used when no authored scenario exists —
     * for instance when a failed browser recording falls back to the API path mid-run.
     *
     * Only parameterless GETs are included: without a scenario there is no ticket context to invent
     * safe values from, and a demo must never mutate a deployed service on a guess.
     */
    fun discoveryScenario(spec: String, maxRoutes: Int = DISCOVERY_ROUTES): ApiScenario? {
        val root = runCatching { JSON.parseToJsonElement(spec).jsonObject }.getOrNull() ?: return null
        val paths = root["paths"] as? JsonObject ?: return null
        val title = ((root["info"] as? JsonObject)?.get("title") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "the deployed service"

        val requests = paths.entries.asSequence()
            .filterNot { it.key.contains('{') }
            .mapNotNull { (path, node) ->
                val get = (node as? JsonObject)?.get("get") as? JsonObject ?: return@mapNotNull null
                if (requiresAuth(get)) return@mapNotNull null
                ApiStep.Request(
                    method = "GET",
                    path = path,
                    headers = mapOf("Accept" to "application/json"),
                    expectStatus = successStatus(get),
                    note = text(get, "summary") ?: "GET $path"
                )
            }
            .take(maxRoutes)
            .toList()

        if (requests.isEmpty()) return null
        return ApiScenario(
            description = "Read-only walk through $title",
            steps = listOf<ApiStep>(
                ApiStep.Note("No authored scenario — walking the endpoints $title declares.")
            ) + requests
        )
    }

    /** The 2xx the operation documents, defaulting to 200 when it declares none. */
    private fun successStatus(operation: JsonObject): Int =
        statusCodes(operation).mapNotNull { it.toIntOrNull() }.firstOrNull { it in 200..299 } ?: 200

    private fun parameterNames(operation: JsonObject): List<String> =
        (operation["parameters"] as? JsonArray)
            ?.mapNotNull { text(it as? JsonObject ?: return@mapNotNull null, "name") }
            .orEmpty()

    private fun statusCodes(operation: JsonObject): List<String> =
        (operation["responses"] as? JsonObject)?.keys?.toList().orEmpty()

    private fun requiresAuth(operation: JsonObject): Boolean =
        (operation["security"] as? JsonArray)?.isNotEmpty() == true

    /**
     * Property names of the operation's JSON request body. `$ref`s are followed one hop into
     * `components/schemas`, which covers the shape virtually every generator emits; anything deeper
     * is left out rather than guessed at.
     */
    private fun requestBodyFields(operation: JsonObject, components: JsonObject?): List<String> {
        val content = (operation["requestBody"] as? JsonObject)?.get("content") as? JsonObject
            ?: return emptyList()
        val schema = content.entries
            .firstOrNull { it.key.contains("json", ignoreCase = true) }
            ?.let { (it.value as? JsonObject)?.get("schema") as? JsonObject }
            ?: return emptyList()
        val resolved = resolveRef(schema, components) ?: schema
        return (resolved["properties"] as? JsonObject)?.keys?.take(MAX_BODY_FIELDS).orEmpty()
    }

    private fun resolveRef(schema: JsonObject, components: JsonObject?): JsonObject? {
        val ref = text(schema, "\$ref") ?: return null
        val name = ref.substringAfterLast('/').takeIf { it.isNotBlank() } ?: return null
        return (components?.get("schemas") as? JsonObject)?.get(name) as? JsonObject
    }

    private fun text(node: JsonObject, key: String): String? =
        (node[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
}
