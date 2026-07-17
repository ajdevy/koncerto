package com.flexsentlabs.koncerto.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier

class PrometheusMetricsBinder(
    private val metricsRepository: MetricsRepository,
    private val quotaRemainingSuppliers: Map<String, Supplier<Double>> = emptyMap(),
    refreshIntervalMs: Long = 15_000L,
    /** Optional review telemetry source (Epic 18); null → review gauges are not registered. */
    private val reviewMetricsRepository: ReviewMetricsRepository? = null
) : MeterBinder {

    private val cache = AtomicReference<List<IssueMetrics>>(emptyList())
    private val reviewCache = AtomicReference<ReviewBaseline?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Load once synchronously so gauge snapshots in bindTo() see real data on startup.
        // Constructor runs on a regular JVM thread (not a reactive dispatcher), so runBlocking is safe here.
        try { cache.set(runBlocking { metricsRepository.findAll() }) } catch (_: Exception) {}
        try { reviewCache.set(runBlocking { reviewMetricsRepository?.baseline(null, 30) }) } catch (_: Exception) {}
        scope.launch {
            while (true) {
                delay(refreshIntervalMs)
                try { cache.set(metricsRepository.findAll()) } catch (_: Exception) {}
                try { reviewCache.set(reviewMetricsRepository?.baseline(null, 30)) } catch (_: Exception) {}
            }
        }
    }

    override fun bindTo(registry: MeterRegistry) {
        bindTotalRuns(registry)
        bindTotalTokens(registry)
        bindRunsByProject(registry)
        bindRunsByState(registry)
        bindQuotaRemaining(registry)
        if (reviewMetricsRepository != null) bindReviewMetrics(registry)
    }

    /**
     * Review-quality gauges (Epic 18). Signal-to-cost lives or dies on these being visible:
     * findings volume alone is the metric the talk warns against, so publish the outcome and
     * cost ratios next to it.
     */
    private fun bindReviewMetrics(registry: MeterRegistry) {
        fun gauge(name: String, desc: String, tags: List<Pair<String, String>> = emptyList(),
                  extract: (ReviewBaseline) -> Double) {
            Gauge.builder(name, reviewCache) { c -> c.get()?.let(extract) ?: 0.0 }
                .description(desc)
                .apply { tags.forEach { (k, v) -> tag(k, v) } }
                .register(registry)
        }

        gauge("koncerto_review_runs_total", "Review runs in the last 30d",
            listOf("eligibility" to "all")) { it.totalRuns.toDouble() }
        gauge("koncerto_review_runs_total", "Review runs that invoked a model",
            listOf("eligibility" to "reviewed")) { it.reviewedRuns.toDouble() }
        gauge("koncerto_review_runs_total", "Review runs skipped by the eligibility check",
            listOf("eligibility" to "skipped")) { it.skippedRuns.toDouble() }
        gauge("koncerto_review_runs_fallback_total", "Runs whose structured parse fell back") {
            it.fallbackRuns.toDouble()
        }
        gauge("koncerto_review_findings_total", "All findings produced",
            listOf("published" to "all")) { it.totalFindings.toDouble() }
        gauge("koncerto_review_findings_total", "Findings that cleared the publication gate",
            listOf("published" to "true")) { it.publishedFindings.toDouble() }
        gauge("koncerto_review_high_evidence_rate", "Share of published findings that were fixed or discussed") {
            it.highEvidenceRate
        }
        gauge("koncerto_review_false_positive_rate", "Share of human-labeled findings marked false positive") {
            it.falsePositiveRate
        }
        gauge("koncerto_review_tokens_total", "Tokens consumed by review runs") { it.totalTokens.toDouble() }
        gauge("koncerto_review_tokens_per_useful_finding", "Cost-adjusted utility: tokens per high-evidence finding") {
            it.tokensPerUsefulFinding
        }
    }

    private fun bindTotalRuns(registry: MeterRegistry) {
        Gauge.builder("koncerto_agent_runs_total", cache) { c ->
            c.get().sumOf { it.totalRuns }.toDouble()
        }.description("Total number of agent runs across all issues")
            .tag("type", "total")
            .register(registry)
    }

    private fun bindTotalTokens(registry: MeterRegistry) {
        Gauge.builder("koncerto_agent_tokens_total", cache) { c ->
            c.get().sumOf { it.totalInputTokens }.toDouble()
        }.description("Total input tokens consumed by agent runs")
            .tag("type", "input")
            .register(registry)

        Gauge.builder("koncerto_agent_tokens_total", cache) { c ->
            c.get().sumOf { it.totalOutputTokens }.toDouble()
        }.description("Total output tokens generated by agent runs")
            .tag("type", "output")
            .register(registry)

        Gauge.builder("koncerto_agent_tokens_total", cache) { c ->
            c.get().sumOf { it.totalTokens }.toDouble()
        }.description("Total tokens (input + output) for agent runs")
            .tag("type", "total")
            .register(registry)
    }

    private fun bindRunsByProject(registry: MeterRegistry) {
        val snapshot = cache.get()
        snapshot.filter { it.projectSlug != null }
            .groupBy { it.projectSlug!! }
            .forEach { (project, metrics) ->
                val count = metrics.sumOf { it.totalRuns }
                Gauge.builder("koncerto_agent_runs_by_project", Supplier { count.toDouble() })
                    .description("Total agent runs for project")
                    .tag("project", project)
                    .register(registry)
            }
    }

    private fun bindRunsByState(registry: MeterRegistry) {
        val snapshot = cache.get()
        snapshot.filter { it.lastResult != null }
            .groupBy { it.lastResult!! }
            .forEach { (state, metrics) ->
                val count = metrics.sumOf { it.totalRuns }
                Gauge.builder("koncerto_agent_runs_by_state", Supplier { count.toDouble() })
                    .description("Total agent runs for state")
                    .tag("state", state)
                    .register(registry)
            }
    }

    private fun bindQuotaRemaining(registry: MeterRegistry) {
        for ((project, supplier) in quotaRemainingSuppliers) {
            Gauge.builder("koncerto_quota_remaining", supplier) { supplier.get() }
                .description("Remaining quota capacity for project")
                .tag("project", project)
                .register(registry)
        }
    }
}
