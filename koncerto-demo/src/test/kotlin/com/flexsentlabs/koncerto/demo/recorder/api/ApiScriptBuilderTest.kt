package com.flexsentlabs.koncerto.demo.recorder.api

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class ApiScriptBuilderTest {

    private fun request(
        method: String = "GET",
        path: String = "/api/items",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        expectStatus: Int? = null,
        expectContains: String? = null,
        save: Map<String, String> = emptyMap(),
        note: String = ""
    ) = ApiStep.Request(method, path, headers, body, expectStatus, expectContains, save, note)

    @Test
    fun `shellQuote makes text literal and survives embedded quotes`() {
        assert(ApiScriptBuilder.shellQuote("plain") == "'plain'")
        assert(ApiScriptBuilder.shellQuote("it's") == "'it'\\''s'")
    }

    @Test
    fun `escapeForDoubleQuotes neutralises every character bash still expands`() {
        assert(ApiScriptBuilder.escapeForDoubleQuotes("a\"b") == "a\\\"b")
        assert(ApiScriptBuilder.escapeForDoubleQuotes("\$HOME") == "\\\$HOME")
        assert(ApiScriptBuilder.escapeForDoubleQuotes("`id`") == "\\`id\\`")
        assert(ApiScriptBuilder.escapeForDoubleQuotes("a\\b") == "a\\\\b")
    }

    @Test
    fun `interpolate turns placeholders into saved shell variables`() {
        assert(ApiScriptBuilder.interpolate("/items/{{item_id}}") == "/items/\${SAVED_item_id}")
        assert(ApiScriptBuilder.interpolate("{{ a }}-{{b}}") == "\${SAVED_a}-\${SAVED_b}")
        assert(ApiScriptBuilder.interpolate("no placeholder") == "no placeholder")
    }

    @Test
    fun `interpolate keeps literal shell syntax around a placeholder inert`() {
        val rendered = ApiScriptBuilder.interpolate("\$PATH {{tok}} `id`")

        assert(rendered == "\\\$PATH \${SAVED_tok} \\`id\\`")
    }

    @Test
    fun `curlCommand renders the argument vector with the base URL prefixed`() {
        val command = ApiScriptBuilder.curlCommand(request(method = "GET", path = "/api/items"))

        assert(command == "curl -sS -i -X \"GET\" \"\$TARGET_URL/api/items\"")
    }

    @Test
    fun `curlCommand adds a leading slash to a relative path`() {
        val command = ApiScriptBuilder.curlCommand(request(path = "api/items"))

        assert(command.contains("\"\$TARGET_URL/api/items\""))
    }

    @Test
    fun `curlCommand renders headers and a body`() {
        val command = ApiScriptBuilder.curlCommand(
            request(
                method = "POST",
                path = "/api/items",
                headers = mapOf("Content-Type" to "application/json"),
                body = """{"name":"Demo"}"""
            )
        )

        assert(command.contains(" -H \"Content-Type: application/json\""))
        assert(command.contains(" --data-raw \"{\\\"name\\\":\\\"Demo\\\"}\""))
    }

    @Test
    fun `curlCommand interpolates saved values into path header and body`() {
        val command = ApiScriptBuilder.curlCommand(
            request(
                method = "PUT",
                path = "/api/items/{{item_id}}",
                headers = mapOf("Authorization" to "Bearer {{API_TOKEN}}"),
                body = """{"owner":"{{user}}"}"""
            )
        )

        assert(command.contains("\"\$TARGET_URL/api/items/\${SAVED_item_id}\""))
        assert(command.contains("-H \"Authorization: Bearer \${SAVED_API_TOKEN}\""))
        assert(command.contains("\${SAVED_user}"))
    }

    @Test
    fun `build emits a runnable script with preamble banner and epilogue`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("Items API", listOf(request())),
            resultPath = "/work/api-result.jsonl",
            issueIdentifier = "FLE-99"
        )

        assert(script.startsWith("#!/bin/bash"))
        assert(script.contains("RESULT_FILE='/work/api-result.jsonl'"))
        assert(script.contains("=== FLE-99 — Items API ==="))
        assert(script.contains("type_out \"\$(render_cmd \"\${CMD[@]}\")\""))
        assert(script.contains("\"\${CMD[@]}\" > /tmp/response.txt 2>&1"))
        assert(script.trimEnd().endsWith("exit 0"))
    }

    @Test
    fun `build titles the demo from whichever of identifier and description is present`() {
        val noIdentifier = ApiScriptBuilder.build(
            ApiScenario("Only description", listOf(request())), "/r", issueIdentifier = ""
        )
        assert(noIdentifier.contains("=== Only description ==="))

        val neither = ApiScriptBuilder.build(ApiScenario("", listOf(request())), "/r")
        assert(neither.contains("=== API demo ==="))
    }

    @Test
    fun `build escapes a heading that carries shell syntax`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("cost is \$5 `now`", listOf(request())), "/r"
        )

        assert(script.contains("=== cost is \\\$5 \\`now\\` ==="))
    }

    @Test
    fun `a request without assertions records its outcome but asserts nothing`() {
        val script = ApiScriptBuilder.build(ApiScenario("d", listOf(request())), "/r")

        assert(!script.contains("expected status"))
        assert(!script.contains("grep -qF"))
        assert(script.contains("python3 /tmp/record.py 1 'GET' '/api/items'"))
    }

    @Test
    fun `status assertion emits both branches and marks the step failed`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(expectStatus = 201))), "/r"
        )

        assert(script.contains("if [ \"\$CODE\" = \"201\" ]; then"))
        assert(script.contains("pass \"status \$CODE\""))
        assert(script.contains("fail \"expected status 201 but got \$CODE\""))
        assert(script.contains("STEP_OK=0; STEP_ERROR=\"expected 201 got \$CODE\""))
        assert(script.contains("'201'"))
    }

    @Test
    fun `body assertion greps the response body`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(expectContains = "Demo item"))), "/r"
        )

        assert(script.contains("if grep -qF 'Demo item' /tmp/body.txt; then"))
        assert(script.contains("fail \"response does not contain Demo item\""))
    }

    @Test
    fun `a long expect_contains is truncated in the on-screen message only`() {
        val long = "x".repeat(100)
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(expectContains = long))), "/r"
        )

        assert(script.contains("grep -qF '$long'"))
        assert(script.contains("pass \"response contains ${"x".repeat(60)}\""))
    }

    @Test
    fun `save emits a jsonpath extraction with a failure branch`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(save = mapOf("item_id" to "data.id")))), "/r"
        )

        assert(script.contains("if SAVED_item_id=\$(python3 /tmp/jsonpath.py 'data.id' /tmp/body.txt); then"))
        assert(script.contains("fail \"could not read data.id from the response\""))
        assert(script.contains("STEP_ERROR=\"save item_id failed\""))
    }

    @Test
    fun `notes are rendered above their request`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(note = "Create it"))), "/r"
        )

        assert(script.contains("note 'Create it'"))
        assert(script.indexOf("note 'Create it'") < script.indexOf("CMD=("))
    }

    @Test
    fun `note steps become narration beats`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(ApiStep.Note("Section one"), request())), "/r"
        )

        assert(script.contains("note 'Section one'\nsleep 1"))
    }

    @Test
    fun `wait steps convert to seconds and are capped`() {
        val script = ApiScriptBuilder.build(
            ApiScenario(
                "d",
                listOf(ApiStep.Wait(1500), ApiStep.Wait(99_000), ApiStep.Wait(-5, note = "hold"), request())
            ),
            "/r"
        )

        assert(script.contains("sleep 1.50"))
        assert(script.contains("sleep 10.00"))
        assert(script.contains("note 'hold'\nsleep 0.00"))
    }

    @Test
    fun `steps are numbered in the order they run`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request(path = "/a"), ApiStep.Note("mid"), request(path = "/b"))), "/r"
        )

        assert(script.contains("record.py 1 'GET' '/a'"))
        assert(script.contains("record.py 3 'GET' '/b'"))
    }

    @Test
    fun `the typing delay is configurable`() {
        val script = ApiScriptBuilder.build(
            ApiScenario("d", listOf(request())), "/r", typingDelaySeconds = "0"
        )

        assert(script.contains("TYPING_DELAY='0'"))
    }

    @Test
    fun `the status parse only accepts a real HTTP status line`() {
        val script = ApiScriptBuilder.build(ApiScenario("d", listOf(request())), "/r")

        // curl prints `curl: (7) Failed to connect` when it cannot reach the target; an unanchored
        // parse read "(7)" as the status and reported it as though the server had answered.
        assert(script.contains("awk 'NR==1 && \$1 ~ /^HTTP\\// {print \$2}'"))
        assert(script.contains("[ -z \"\$CODE\" ] && CODE=\"000\""))
    }

    @Test
    fun `the emitted script is valid bash`() {
        // The script is assembled from ticket-authored values, so a quoting mistake would only show
        // up as a container that dies mid-demo. Parsing it here catches that at build time.
        assumeTrue(File("/bin/bash").canExecute(), "bash is required to syntax-check the emitted script")

        val script = ApiScriptBuilder.build(
            ApiScenario(
                "Quoting torture test",
                listOf(
                    ApiStep.Note("it's a \"quoted\" note with \$VAR and `backticks`"),
                    ApiStep.Wait(1500),
                    request(
                        method = "POST",
                        path = "/api/items/{{item_id}}",
                        headers = mapOf("Authorization" to "Bearer {{API_TOKEN}}", "X-Odd" to "a b\"c"),
                        body = "{\"name\":\"it's \$HOME `id`\"}",
                        expectStatus = 201,
                        expectContains = "it's",
                        save = mapOf("item_id" to "data.id"),
                        note = "create"
                    )
                )
            ),
            resultPath = "/work/api-result.jsonl",
            issueIdentifier = "FLE-1 `x` \$y"
        )

        val file = File.createTempFile("api-demo-", ".sh").apply { deleteOnExit(); writeText(script) }
        val process = ProcessBuilder("/bin/bash", "-n", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assert(process.waitFor(30, TimeUnit.SECONDS))
        assert(process.exitValue() == 0) { "bash rejected the generated script:\n$output\n---\n$script" }
    }
}
