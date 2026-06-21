package com.flexsentlabs.koncerto.demo.service

import com.flexsentlabs.koncerto.demo.model.DemoResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DemoCleanupScheduler(
    private val recordingService: DemoRecordingService,
    private val scope: CoroutineScope,
    private val intervalHours: Int = 24
) {
    private var job: Job? = null

    fun start(): Job {
        job?.cancel()
        val intervalMs = intervalHours * 3600_000L
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val result = recordingService.enforceRetentionPolicy()
                    if (result is DemoResult.Failure) {
                        val error = result.error
                    }
                } catch (e: Exception) {

                }
            }
        }
        return job!!
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
