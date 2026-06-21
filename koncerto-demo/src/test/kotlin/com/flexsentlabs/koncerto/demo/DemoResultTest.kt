package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.runDemoCatching
import org.junit.jupiter.api.Test

class DemoResultTest {

    @Test
    fun `success stores value`() {
        val result = DemoResult.Success(42)
        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == 42)
    }

    @Test
    fun `failure stores error`() {
        val error = DemoError.RecordingFailed(RuntimeException("test"))
        val result = DemoResult.Failure(error)
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error.message == "recording_failed: test")
    }

    @Test
    fun `map transforms success`() {
        val result = DemoResult.Success(42)
        val mapped = result.map { it * 2 }
        assert((mapped as DemoResult.Success).value == 84)
    }

    @Test
    fun `map passes through failure`() {
        val result = DemoResult.Failure(DemoError.TaskNotFound("x"))
        val mapped = result.map { it }
        assert(mapped is DemoResult.Failure)
    }

    @Test
    fun `getOrNull returns value on success`() {
        val result = DemoResult.Success("hello")
        assert(result.getOrNull() == "hello")
    }

    @Test
    fun `getOrNull returns null on failure`() {
        val result = DemoResult.Failure(DemoError.TaskNotFound("x"))
        assert(result.getOrNull() == null)
    }

    @Test
    fun `runDemoCatching catches DemoError`() {
        val result = runDemoCatching { throw DemoError.RecorderNotAvailable("test") }
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.RecorderNotAvailable)
    }

    @Test
    fun `runDemoCatching catches generic Exception`() {
        val result = runDemoCatching { throw RuntimeException("boom") }
        assert(result is DemoResult.Failure)
        assert((result as DemoResult.Failure).error is DemoError.RecordingFailed)
    }

    @Test
    fun `runDemoCatching returns success`() {
        val result = runDemoCatching { 42 }
        assert((result as DemoResult.Success).value == 42)
    }
}
