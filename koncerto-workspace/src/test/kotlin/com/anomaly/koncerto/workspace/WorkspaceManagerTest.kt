package com.anomaly.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkspaceManagerTest {

    @Test
    fun `creates workspace and returns path with createdNow true on first creation`() = runTest {
        val root = Files.createTempDirectory("ws-root")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("ABC-1")
        assertThat(ws.path).isEqualTo(root.resolve("ABC-1"))
        assertThat(ws.createdNow).isTrue()
        assertThat(Files.isDirectory(ws.path)).isTrue()
    }

    @Test
    fun `second call to ensureWorkspace returns createdNow false`() = runTest {
        val root = Files.createTempDirectory("ws-root-2")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.ensureWorkspace("ABC-2")
        val ws = mgr.ensureWorkspace("ABC-2")
        assertThat(ws.createdNow).isFalse()
    }

    @Test
    fun `sanitizes identifier with special characters`() = runTest {
        val root = Files.createTempDirectory("ws-root-3")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("My Issue!")
        assertThat(ws.path).isEqualTo(root.resolve("My_Issue_"))
    }

    @Test
    fun `path outside workspace root is rejected`() = runTest {
        val root = Files.createTempDirectory("ws-root-4")
        val mgr = WorkspaceManager(root, noopExecutor())
        assertThrows<IllegalStateException> {
            mgr.assertInsideRoot(Path.of("/etc/passwd"))
        }
    }

    @Test
    fun `assertInsideRoot accepts paths inside root`() = runTest {
        val root = Files.createTempDirectory("ws-root-5")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.assertInsideRoot(root.resolve("subdir"))
    }

    private fun noopExecutor() = HookExecutor { _, _ -> }
}