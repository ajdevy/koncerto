package com.flexsentlabs.koncerto.demo.recorder.api

import org.junit.jupiter.api.Test

class ApiRunResultTest {

    private fun line(step: Int, ok: Boolean, code: String = "200", error: String = "", path: String = "/a") =
        """{"step":$step,"method":"GET","path":"$path","code":"$code","expected":"200","ok":$ok,"error":"$error"}"""

    @Test
    fun `a run where every request passed has no failure reason`() {
        val result = ApiRunResult.parse("${line(1, true)}\n${line(2, true)}", expectedRequests = 2)

        assert(result.executed == 2)
        assert(result.failed.isEmpty())
        assert(result.failureReason() == null)
    }

    @Test
    fun `an empty result means the script issued nothing`() {
        val reason = ApiRunResult.parse("", expectedRequests = 3).failureReason()

        assert(reason == "the demo script issued no requests — the scenario produced no executable step")
    }

    @Test
    fun `a truncated run reports how far it got and why it stopped`() {
        val reason = ApiRunResult.parse(
            "${line(1, true)}\n${line(2, false, code = "500", error = "expected 200 got 500", path = "/b")}",
            expectedRequests = 4
        ).failureReason()!!

        assert(reason.startsWith("the demo stopped after 2 of 4 requests"))
        assert(reason.contains("GET /b -> 500 (expected 200 got 500)"))
    }

    @Test
    fun `a truncated run with no failed step still reports the shortfall`() {
        val reason = ApiRunResult.parse(line(1, true), expectedRequests = 3).failureReason()!!

        assert(reason == "the demo stopped after 1 of 3 requests")
    }

    @Test
    fun `a complete run with failed assertions lists each one`() {
        val reason = ApiRunResult.parse(
            "${line(1, false, code = "404", error = "expected 200 got 404")}\n${line(2, false, code = "500", path = "/b")}",
            expectedRequests = 2
        ).failureReason()!!

        assert(reason.startsWith("2 of 2 requests failed: "))
        assert(reason.contains("GET /a -> 404 (expected 200 got 404)"))
        assert(reason.contains("GET /b -> 500"))
        assert(!reason.contains("/b -> 500 ()"))
    }

    @Test
    fun `a run longer than expected is not treated as truncated`() {
        assert(ApiRunResult.parse("${line(1, true)}\n${line(2, true)}", expectedRequests = 1).failureReason() == null)
    }

    @Test
    fun `malformed and non-record lines are skipped`() {
        val result = ApiRunResult.parse(
            "not json\n\n{ broken\n${line(1, true)}\n[1,2]",
            expectedRequests = 1
        )

        assert(result.executed == 1)
        assert(result.failureReason() == null)
    }

    @Test
    fun `unknown fields in a record do not break parsing`() {
        val result = ApiRunResult.parse(
            """{"step":1,"method":"GET","path":"/a","code":"200","expected":"200","ok":true,"error":"","extra":9}""",
            expectedRequests = 1
        )

        assert(result.executed == 1)
    }

    @Test
    fun `the outcome model defaults to a failed empty record`() {
        val outcome = ApiStepOutcome()

        assert(outcome.step == 0 && outcome.method == "" && outcome.path == "")
        assert(outcome.code == "" && outcome.expected == "" && outcome.error == "")
        assert(!outcome.ok)
    }
}
