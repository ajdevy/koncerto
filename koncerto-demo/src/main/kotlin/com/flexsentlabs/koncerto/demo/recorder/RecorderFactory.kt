package com.flexsentlabs.koncerto.demo.recorder

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult

class RecorderFactory(
    private val recorders: List<DemoRecorder>
) {
    suspend fun findRecorder(platform: DemoPlatform): DemoResult<DemoRecorder> {
        val available = recorders.firstOrNull { it.platform == platform && it.isAvailable() }
        if (available != null) return DemoResult.Success(available)
        val unavailable = recorders.firstOrNull { it.platform == platform }
        return if (unavailable != null) {
            DemoResult.Success(unavailable)
        } else {
            DemoResult.Failure(
                com.flexsentlabs.koncerto.demo.model.DemoError.RecorderNotAvailable(platform.name)
            )
        }
    }

    suspend fun availablePlatforms(): List<DemoPlatform> =
        recorders.filter { it.isAvailable() }.map { it.platform }
}
