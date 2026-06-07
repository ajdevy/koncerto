package com.anomaly.koncerto.dashboard

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.anomaly.koncerto.orchestrator.CodexTotals
import com.anomaly.koncerto.orchestrator.RetryEntry
import com.anomaly.koncerto.orchestrator.RuntimeState
import com.anomaly.koncerto.core.model.Issue
import com.anomaly.koncerto.orchestrator.RunningEntry
import java.time.Instant
import org.junit.jupiter.api.Test

class ApiV1ControllerTest {

    @Test
    fun `state returns snapshot with running and retrying`() {
        val state = RuntimeState()
        state.maxConcurrentAgents = 5

        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            inputTokens = 100,
            outputTokens = 50,
            totalTokens = 150,
            turnCount = 2
        )
        state.retryAttempts["2"] = RetryEntry("2", "ABC-2", 1, System.currentTimeMillis() + 60000, "timeout")

        val controller = ApiV1Controller(state)
        val snapshot = controller.state().block()

        assertThat(snapshot!!.running.size).isEqualTo(1)
        assertThat(snapshot.running[0].issueIdentifier).isEqualTo("ABC-1")
        assertThat(snapshot.running[0].threadId).isEqualTo("t-1")
        assertThat(snapshot.running[0].turnCount).isEqualTo(2)
        assertThat(snapshot.running[0].inputTokens).isEqualTo(100)
        assertThat(snapshot.retrying.size).isEqualTo(1)
        assertThat(snapshot.retrying[0].identifier).isEqualTo("ABC-2")
        assertThat(snapshot.retrying[0].attempt).isEqualTo(1)
    }

    @Test
    fun `byIdentifier returns issue details when found`() {
        val state = RuntimeState()
        val issue = Issue("1", "ABC-1", "Test", null, 1, "Todo", null, null, emptyList(), emptyList(), null, null)
        state.running["1"] = RunningEntry(
            issue = issue,
            threadId = "t-1",
            turnId = "u-1",
            startedAt = Instant.now(),
            lastCodexTimestamp = null,
            turnCount = 3
        )

        val controller = ApiV1Controller(state)
        val result = controller.byIdentifier("ABC-1").block()

        assertThat(result!!["issueId"]).isEqualTo("1")
        assertThat(result["issueIdentifier"]).isEqualTo("ABC-1")
        assertThat(result["threadId"]).isEqualTo("t-1")
        assertThat(result["turnCount"]).isEqualTo(3)
    }

    @Test
    fun `byIdentifier returns not_found when missing`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(state)
        val result = controller.byIdentifier("MISSING").block()

        assertThat(result!!["error"]).isEqualTo("not_found")
    }

    @Test
    fun `refresh returns ok`() {
        val state = RuntimeState()
        val controller = ApiV1Controller(state)
        val result = controller.refresh().block()

        assertThat(result!!["status"]).isEqualTo("ok")
    }
}
