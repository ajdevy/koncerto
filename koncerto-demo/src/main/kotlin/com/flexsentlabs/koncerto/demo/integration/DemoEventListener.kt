package com.flexsentlabs.koncerto.demo.integration

import com.flexsentlabs.koncerto.demo.model.DemoPlatform
import com.flexsentlabs.koncerto.demo.model.DemoResult
import com.flexsentlabs.koncerto.demo.model.DemoTrigger
import com.flexsentlabs.koncerto.demo.service.DemoRecordingService

class DemoEventListener(
    private val recordingService: DemoRecordingService,
    private val enabled: Boolean = true
) {
    suspend fun onReviewPassed(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        targetUrl: String? = null
    ): String? {
        if (!enabled) return null
        val result = recordingService.requestRecording(
            issueId = issueId, issueIdentifier = issueIdentifier,
            projectSlug = projectSlug, platform = null,
            trigger = DemoTrigger.REVIEW_PASSED,
            targetUrl = targetUrl
        )
        return when (result) {
            is DemoResult.Success -> result.value.recordingUrl
            else -> null
        }
    }

    suspend fun onRecordDemoAction(
        issueId: String, issueIdentifier: String, projectSlug: String?,
        platform: DemoPlatform? = null
    ): DemoResult<*> {
        if (!enabled) return DemoResult.Success(Unit)
        return recordingService.requestRecording(
            issueId = issueId, issueIdentifier = issueIdentifier,
            projectSlug = projectSlug, platform = platform,
            trigger = DemoTrigger.MANUAL
        )
    }
}
