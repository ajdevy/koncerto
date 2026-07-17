package com.flexsentlabs.koncerto.dashboard

import com.flexsentlabs.koncerto.core.review.HumanLabel
import com.flexsentlabs.koncerto.metrics.ReviewBaseline
import com.flexsentlabs.koncerto.metrics.ReviewFindingRecord
import com.flexsentlabs.koncerto.metrics.ReviewMetricsRepository
import com.flexsentlabs.koncerto.metrics.ReviewRunRecord
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Review-quality API (Epics 18/22): run + finding telemetry, human feedback labels, and the
 * baseline/calibration aggregate. Sits behind the dashboard's existing auth.
 */
@RestController
@RequestMapping("/api/v1/review")
class ReviewController @Autowired constructor(
    private val reviewMetrics: ReviewMetricsRepository? = null
) {

    @GetMapping("/runs", produces = ["application/json"])
    suspend fun runs(
        @RequestParam("project", required = false) project: String? = null,
        @RequestParam("limit", defaultValue = "200") limit: Int = 200
    ): List<ReviewRunRecord> = reviewMetrics?.runs(project, limit) ?: emptyList()

    @GetMapping("/runs/{runId}/findings", produces = ["application/json"])
    suspend fun findings(@PathVariable runId: String): List<ReviewFindingRecord> =
        reviewMetrics?.findingsForRun(runId) ?: emptyList()

    /**
     * Human feedback on a published finding. This is the calibration ground truth, so the
     * label vocabulary is validated rather than stored free-form.
     */
    @PostMapping("/findings/{findingId}/label", produces = ["application/json"])
    suspend fun label(
        @PathVariable findingId: String,
        @RequestParam("label") label: String
    ): ResponseEntity<Map<String, String>> {
        val parsed = HumanLabel.fromWire(label)
            ?: return ResponseEntity.badRequest().body(
                mapOf("error" to "invalid label; expected accept|reject|false_positive")
            )
        val repo = reviewMetrics
            ?: return ResponseEntity.status(503).body(mapOf("error" to "review metrics unavailable"))
        repo.setHumanLabel(findingId, parsed.name.lowercase())
        return ResponseEntity.ok(mapOf("findingId" to findingId, "label" to parsed.name.lowercase()))
    }

    @GetMapping("/baseline", produces = ["application/json"])
    suspend fun baseline(
        @RequestParam("project", required = false) project: String? = null,
        @RequestParam("window", defaultValue = "30") windowDays: Int = 30
    ): ReviewBaseline? = reviewMetrics?.baseline(project, windowDays)
}
