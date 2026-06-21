package com.flexsentlabs.koncerto.demo.report

import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTask

interface DemoReporter {
    suspend fun report(task: DemoTask, recordingUrl: String): DemoResult<Unit>
    suspend fun reportFailure(task: DemoTask, errorMessage: String): DemoResult<Unit>
}
