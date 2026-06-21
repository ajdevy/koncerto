package com.flexsentlabs.koncerto.demo

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.recorder.DemoRecorder
import com.flexsentlabs.koncerto.demo.recorder.RecorderFactory
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RecorderFactoryTest {

    @Test
    fun `findRecorder returns recorder when available`() = runTest {
        val factory = RecorderFactory(listOf(AvailableRecorder()))
        val result = factory.findRecorder(DemoPlatform.PLAYWRIGHT)
        assert(result is DemoResult.Success)
    }

    @Test
    fun `findRecorder returns failure when not found`() = runTest {
        val factory = RecorderFactory(emptyList())
        val result = factory.findRecorder(DemoPlatform.PLAYWRIGHT)
        assert(result is DemoResult.Failure)
    }

    @Test
    fun `findRecorder skips unavailable and falls back to available`() = runTest {
        val factory = RecorderFactory(listOf(UnavailableRecorder(), AvailableRecorder()))
        val result = factory.findRecorder(DemoPlatform.PLAYWRIGHT)
        assert(result is DemoResult.Success)
    }

    @Test
    fun `availablePlatforms returns only available recorders`() = runTest {
        val factory = RecorderFactory(listOf(UnavailableRecorder(), AvailableRecorder()))
        val platforms = factory.availablePlatforms()
        assert(platforms.size == 1)
        assert(platforms.contains(DemoPlatform.PLAYWRIGHT))
    }
}

class AvailableRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT

    override suspend fun isAvailable(): Boolean = true

    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig,
        outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        return DemoResult.Success(DemoRecorder.RecordingResult(
            file = outputFile, durationMs = 0, fileSizeBytes = 0, format = "webm"
        ))
    }
}

class UnavailableRecorder : DemoRecorder {
    override val platform: DemoPlatform = DemoPlatform.PLAYWRIGHT

    override suspend fun isAvailable(): Boolean = false

    override suspend fun record(
        config: com.flexsentlabs.koncerto.demo.model.RecordingConfig,
        outputFile: File
    ): DemoResult<DemoRecorder.RecordingResult> {
        return DemoResult.Failure(
            com.flexsentlabs.koncerto.demo.model.DemoError.RecorderNotAvailable("playwright")
        )
    }
}
