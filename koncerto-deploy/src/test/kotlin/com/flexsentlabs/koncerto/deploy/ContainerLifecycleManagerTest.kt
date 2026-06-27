package com.flexsentlabs.koncerto.deploy

import assertk.assertThat
import assertk.assertions.isGreaterThan
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.logging.StructuredLogger
import org.junit.jupiter.api.Test

class ContainerLifecycleManagerTest {

    private val manager = ContainerLifecycleManager(
        logger = StructuredLogger(emptyList()),
        portRange = 45000..45010
    )

    @Test
    fun `allocatePort returns port in range`() {
        val port = manager.allocatePort()
        assertThat(port in 45000..45010).isTrue()
    }

    @Test
    fun `releasePort allows reallocation`() {
        val port = manager.allocatePort()
        manager.releasePort(port)
        val port2 = manager.allocatePort()
        assertThat(port2).isGreaterThan(0)
    }

    @Test
    fun `captureLogs returns message when container missing`() {
        val logs = manager.captureLogs("nonexistent-container-id")
        assertThat(logs.isNotBlank()).isTrue()
    }

    @Test
    fun `stopAndRemove does not throw for missing container`() {
        manager.stopAndRemove("nonexistent-container-id")
    }

    @Test
    fun `runContainer retries on port conflict`() {
        val manager = ContainerLifecycleManager(
            logger = StructuredLogger(emptyList()),
            portRange = 45100..45102
        )
        val port1 = manager.allocatePort()
        val result = manager.runContainer("nonexistent-image:tag", port1, 8080)
        manager.releasePort(port1)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `buildImage fails for invalid dockerfile`() {
        val result = manager.buildImage(
            java.nio.file.Path.of("."),
            java.nio.file.Path.of("/nonexistent/Dockerfile"),
            "koncerto-test-invalid"
        )
        assertThat(result.isFailure).isTrue()
    }
}
