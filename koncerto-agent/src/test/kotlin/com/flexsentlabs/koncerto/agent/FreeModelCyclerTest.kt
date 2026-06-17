package com.flexsentlabs.koncerto.agent

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEmpty
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.result.Result
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class FreeModelCyclerTest {

    private fun noopLogger() = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `constructor requires at least one model`() = runTest {
        try {
            FreeModelCycler(emptyList(), 3, noopLogger())
            throw AssertionError("expected exception")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `constructor requires positive maxRetriesPerModel`() = runTest {
        try {
            FreeModelCycler(listOf("model1"), 0, noopLogger())
            throw AssertionError("expected exception")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).isNotNull()
        }
    }

    @Test
    fun `nextModel returns first model initially`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b", "model-c"), 3, noopLogger())
        val result = cycler.nextModel()
        assertThat(result is Result.Success).isTrue()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
    }

    @Test
    fun `nextModel cycles through models when retries exhausted`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 1, noopLogger())
        
        // First call returns model-a
        var result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        
        // Report failure for model-a
        cycler.reportFailure("model-a")
        
        // Next call should return model-b (since model-a retries exhausted)
        result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-b")
    }

    @Test
    fun `nextModel returns failure when all models exhausted`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 1, noopLogger())
        
        // Exhaust model-a
        var result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        cycler.reportFailure("model-a")
        
        // Exhaust model-b
        result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-b")
        cycler.reportFailure("model-b")
        
        // Now all models exhausted
        result = cycler.nextModel()
        assertThat(result is Result.Failure).isTrue()
        val failure = result as Result.Failure<ModelExhaustedException>
        assertThat(failure.error.modelsTried).isEqualTo(listOf("model-a", "model-b"))
        assertThat(failure.error.totalRetries).isEqualTo(2)
    }

    @Test
    fun `reportSuccess resets retry count for model`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 2, noopLogger())
        
        var result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        
        // Fail once
        cycler.reportFailure("model-a")
        
        // Should still return model-a (1 retry out of 2)
        result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        
        // Report success - resets retry count
        cycler.reportSuccess("model-a")
        
        // Should return model-a again (retry count reset)
        result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
    }

    @Test
    fun `reset clears all state`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 1, noopLogger())
        
        var result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        cycler.reportFailure("model-a")
        
        result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-b")
        cycler.reportFailure("model-b")
        
        // Now exhausted
        result = cycler.nextModel()
        assertThat(result is Result.Failure).isTrue()
        
        // Reset
        cycler.reset()
        
        // Should work again
        result = cycler.nextModel()
        assertThat(result is Result.Success).isTrue()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
    }

    @Test
    fun `getStatus returns current state`() = runTest {
        val cycler = FreeModelCycler(listOf("model-a", "model-b"), 2, noopLogger())
        
        val status = cycler.getStatus()
        assertThat(status["current_index"]).isEqualTo(0)
        assertThat(status["models"]).isEqualTo(listOf("model-a", "model-b"))
        assertThat(status["retry_counts"]).isEqualTo(emptyMap<String, Int>())
        assertThat(status["exhausted"]).isEqualTo(false)
        assertThat(status["max_retries_per_model"]).isEqualTo(2)
        
        var result = cycler.nextModel()
        assertThat((result as Result.Success).value).isEqualTo("model-a")
        cycler.reportFailure("model-a")
        
        val status2 = cycler.getStatus()
        assertThat(status2["current_index"]).isEqualTo(0)
        assertThat(status2["retry_counts"]).isEqualTo(mapOf("model-a" to 1))
    }

    @Test
    fun `createDefault creates cycler with default models`() = runTest {
        val cycler = FreeModelCycler.createDefault(noopLogger())
        
        val status = cycler.getStatus()
        assertThat(status["models"]).isEqualTo(FreeModelCycler.DEFAULT_FREE_MODELS)
        assertThat(status["max_retries_per_model"]).isEqualTo(3)
    }

    @Test
    fun `createDefault accepts custom maxRetries`() = runTest {
        val cycler = FreeModelCycler.createDefault(noopLogger(), 5)
        
        val status = cycler.getStatus()
        assertThat(status["max_retries_per_model"]).isEqualTo(5)
    }

    @Test
    fun `ModelExhaustedException stores all properties`() {
        val exception = ModelExhaustedException(
            modelsTried = listOf("model-a", "model-b"),
            totalRetries = 5,
            lastError = "rate limit"
        )
        assertThat(exception.modelsTried).isEqualTo(listOf("model-a", "model-b"))
        assertThat(exception.totalRetries).isEqualTo(5)
        assertThat(exception.lastError).isEqualTo("rate limit")
        val msg = exception.message ?: ""
        assertThat(msg.indexOf("All free models exhausted") >= 0).isTrue()
    }
}