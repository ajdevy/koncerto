package com.flexsentlabs.koncerto.metrics

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.size
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PrometheusMetricsBinderTest {

    private lateinit var repo: SqliteMetricsRepository
    private lateinit var registry: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        repo = SqliteMetricsRepository(":memory:")
        registry = SimpleMeterRegistry()
    }

    @Test
    fun `bindTo registers all gauge types with correct values`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 100, 50, 150)
        repo.updateAfterRun("2", "PROJ-2", "proj-b", "failure", 200, 80, 280)
        repo.updateAfterRun("3", "PROJ-3", "proj-a", "success", 30, 10, 40)

        val quotaSuppliers = mapOf(
            "proj-a" to Supplier { 75.0 },
            "proj-b" to Supplier { 50.0 }
        )
        val binder = PrometheusMetricsBinder(repo, quotaSuppliers)
        binder.bindTo(registry)

        val totalRuns = registry.find("koncerto_agent_runs_total")
            .tag("type", "total").gauge()
        assertThat(totalRuns).isNotNull()
        assertThat(totalRuns!!.value()).isEqualTo(3.0)

        val inputTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "input").gauge()
        assertThat(inputTokens).isNotNull()
        assertThat(inputTokens!!.value()).isEqualTo(330.0)

        val outputTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "output").gauge()
        assertThat(outputTokens).isNotNull()
        assertThat(outputTokens!!.value()).isEqualTo(140.0)

        val totalTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "total").gauge()
        assertThat(totalTokens).isNotNull()
        assertThat(totalTokens!!.value()).isEqualTo(470.0)

        val projARuns = registry.find("koncerto_agent_runs_by_project")
            .tag("project", "proj-a").gauge()
        assertThat(projARuns).isNotNull()
        assertThat(projARuns!!.value()).isEqualTo(2.0)

        val projBRuns = registry.find("koncerto_agent_runs_by_project")
            .tag("project", "proj-b").gauge()
        assertThat(projBRuns).isNotNull()
        assertThat(projBRuns!!.value()).isEqualTo(1.0)

        val successRuns = registry.find("koncerto_agent_runs_by_state")
            .tag("state", "success").gauge()
        assertThat(successRuns).isNotNull()
        assertThat(successRuns!!.value()).isEqualTo(2.0)

        val failureRuns = registry.find("koncerto_agent_runs_by_state")
            .tag("state", "failure").gauge()
        assertThat(failureRuns).isNotNull()
        assertThat(failureRuns!!.value()).isEqualTo(1.0)

        val quotaA = registry.find("koncerto_quota_remaining")
            .tag("project", "proj-a").gauge()
        assertThat(quotaA).isNotNull()
        assertThat(quotaA!!.value()).isEqualTo(75.0)

        val quotaB = registry.find("koncerto_quota_remaining")
            .tag("project", "proj-b").gauge()
        assertThat(quotaB).isNotNull()
        assertThat(quotaB!!.value()).isEqualTo(50.0)
    }

    @Test
    fun `bindTotalRuns with empty data returns zero`() = runBlocking {
        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val totalRuns = registry.find("koncerto_agent_runs_total")
            .tag("type", "total").gauge()
        assertThat(totalRuns).isNotNull()
        assertThat(totalRuns!!.value()).isEqualTo(0.0)
    }

    @Test
    fun `bindTotalTokens with empty data returns zero`() = runBlocking {
        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val inputTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "input").gauge()
        assertThat(inputTokens).isNotNull()
        assertThat(inputTokens!!.value()).isEqualTo(0.0)

        val outputTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "output").gauge()
        assertThat(outputTokens).isNotNull()
        assertThat(outputTokens!!.value()).isEqualTo(0.0)

        val totalTokens = registry.find("koncerto_agent_tokens_total")
            .tag("type", "total").gauge()
        assertThat(totalTokens).isNotNull()
        assertThat(totalTokens!!.value()).isEqualTo(0.0)
    }

    @Test
    fun `bindRunsByProject skips null project slugs`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", null, "success", 10, 5, 15)
        repo.updateAfterRun("2", "PROJ-2", "proj-a", "failure", 20, 10, 30)

        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val projARuns = registry.find("koncerto_agent_runs_by_project")
            .tag("project", "proj-a").gauge()
        assertThat(projARuns).isNotNull()
        assertThat(projARuns!!.value()).isEqualTo(1.0)

        val metersWithProjNull = registry.find("koncerto_agent_runs_by_project")
            .tag("project", "proj-null").gauge()
        assertThat(metersWithProjNull).isNull()
    }

    @Test
    fun `bindRunsByProject with no projects registers no gauges`() = runBlocking {
        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val meters = registry.find("koncerto_agent_runs_by_project").meters()
        assertThat(meters).size().isEqualTo(0)
    }

    @Test
    fun `bindRunsByState with mixed results groups correctly`() = runBlocking {
        repo.updateAfterRun("1", "PROJ-1", "proj-a", "success", 10, 5, 15)
        repo.updateAfterRun("2", "PROJ-2", "proj-b", "success", 20, 10, 30)
        repo.updateAfterRun("3", "PROJ-3", "proj-a", "failure", 5, 2, 7)

        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val successRuns = registry.find("koncerto_agent_runs_by_state")
            .tag("state", "success").gauge()
        assertThat(successRuns).isNotNull()
        assertThat(successRuns!!.value()).isEqualTo(2.0)

        val failureRuns = registry.find("koncerto_agent_runs_by_state")
            .tag("state", "failure").gauge()
        assertThat(failureRuns).isNotNull()
        assertThat(failureRuns!!.value()).isEqualTo(1.0)
    }

    @Test
    fun `bindRunsByState with no results registers no gauges`() = runBlocking {
        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val meters = registry.find("koncerto_agent_runs_by_state").meters()
        assertThat(meters).size().isEqualTo(0)
    }

    @Test
    fun `bindQuotaRemaining with empty map registers nothing`() = runBlocking {
        val binder = PrometheusMetricsBinder(repo, emptyMap())
        binder.bindTo(registry)

        val meters = registry.find("koncerto_quota_remaining").meters()
        assertThat(meters).size().isEqualTo(0)
    }

    @Test
    fun `bindTo handles all methods gracefully with empty repo and no quota`() {
        val binder = PrometheusMetricsBinder(repo)
        binder.bindTo(registry)

        val totalRuns = registry.find("koncerto_agent_runs_total")
            .tag("type", "total").gauge()
        assertThat(totalRuns).isNotNull()
    }
}
