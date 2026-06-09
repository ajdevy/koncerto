package com.anomaly.koncerto.app

import assertk.assertThat
import assertk.assertions.isNotNull
import com.anomaly.koncerto.core.config.ServiceConfig
import com.anomaly.koncerto.logging.StructuredLogger
import com.anomaly.koncerto.metrics.MetricsRepository
import com.anomaly.koncerto.orchestrator.Orchestrator
import com.anomaly.koncerto.workflow.WorkflowCache
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.test.context.ContextConfiguration

@SpringBootTest(
    properties = [
        "spring.main.web-application-type=none",
        "koncerto.workflow-path=${'$'}{java.io.tmpdir}/koncerto-ctx-test/WORKFLOW.md"
    ]
)
@ContextConfiguration(initializers = [SpringBootContextTest.TestInitializer::class])
class SpringBootContextTest {

    @Autowired lateinit var config: ServiceConfig
    @Autowired lateinit var logger: StructuredLogger
    @Autowired lateinit var orchestrator: Orchestrator
    @Autowired lateinit var workflowCache: WorkflowCache
    @Autowired lateinit var metricsRepository: MetricsRepository

    @Test
    fun `context loads all beans`() {
        assertThat(config).isNotNull()
        assertThat(logger).isNotNull()
        assertThat(orchestrator).isNotNull()
        assertThat(workflowCache).isNotNull()
        assertThat(metricsRepository).isNotNull()
    }

    @AfterEach
    fun cleanup() {
        orchestrator.stop()
    }

    class TestInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
        override fun initialize(ctx: ConfigurableApplicationContext) {
            val dir = Path.of(System.getProperty("java.io.tmpdir"), "koncerto-ctx-test")
            Files.createDirectories(dir)
            val workflow = dir.resolve("WORKFLOW.md")
            if (!Files.exists(workflow)) {
                Files.writeString(workflow, """
                    |---
                    |poll_interval_ms: 30000
                    |hooks:
                    |  timeout_ms: 60000
                    |projects:
                    |  test:
                    |    tracker:
                    |      kind: linear
                    |      api_key: ctx-test-key
                    |      project_slug: CTX
                    |      active_states:
                    |        - Todo
                    |      terminal_states:
                    |        - Done
                    |    workspace:
                    |      root: /tmp/koncerto-ctx-workspaces
                    |    agent:
                    |      kind: opencode
                    |      max_concurrent_agents: 1
                    |      max_turns: 2
                    |---
                """.trimMargin())
            }
        }
    }
}
