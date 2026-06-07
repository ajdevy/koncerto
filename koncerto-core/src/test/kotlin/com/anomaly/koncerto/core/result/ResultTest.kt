package com.anomaly.koncerto.core.result

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import assertk.assertions.isSameAs
import org.junit.jupiter.api.Test

class ResultTest {

    @Test
    fun `Success holds value`() {
        val result = Result.Success(42)
        assertThat(result.value).isEqualTo(42)
    }

    @Test
    fun `Failure holds error`() {
        val error = RuntimeException("boom")
        val result = Result.Failure(error)
        assertThat(result.error).isSameAs(error)
    }

    @Test
    fun `map transforms Success value`() {
        val result: Result<Int, RuntimeException> = Result.Success(2)
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(Result.Success(6))
    }

    @Test
    fun `map preserves Failure`() {
        val error = RuntimeException("boom")
        val result: Result<Int, RuntimeException> = Result.Failure(error)
        val mapped = result.map { it * 3 }
        assertThat(mapped).isEqualTo(Result.Failure(error))
    }

    @Test
    fun `onSuccess runs block for Success`() {
        var captured: Int? = null
        val result: Result<Int, RuntimeException> = Result.Success(5)
        result.onSuccess { captured = it }
        assertThat(captured).isEqualTo(5)
    }

    @Test
    fun `onSuccess does not run for Failure`() {
        var captured: Int? = null
        val result: Result<Int, RuntimeException> = Result.Failure(RuntimeException())
        result.onSuccess { captured = it }
        assertThat(captured).isNull()
    }

    @Test
    fun `onFailure runs block for Failure`() {
        val error = RuntimeException("boom")
        var captured: Throwable? = null
        val result: Result<Int, RuntimeException> = Result.Failure(error)
        result.onFailure { captured = it }
        assertThat(captured).isSameAs(error)
    }
}
