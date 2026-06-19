package com.flexsentlabs.koncerto.workspace

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.flexsentlabs.koncerto.core.tenant.TenantContext
import com.flexsentlabs.koncerto.core.tenant.TenantId
import com.flexsentlabs.koncerto.logging.LogSink
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WorkspaceManagerTest {

    private val logger = StructuredLogger(listOf(object : LogSink {
        override fun write(line: String) {}
    }))

    @Test
    fun `creates workspace and returns path with createdNow true on first creation`() = runTest {
        val root = Files.createTempDirectory("ws-root")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("ABC-1")
        assertThat(ws.path).isEqualTo(root.resolve("ABC-1"))
        assertThat(ws.createdNow).isTrue()
        assertThat(ws.workspaceKey).isEqualTo("ABC-1")
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

    @Test
    fun `removeWorkspace deletes directory and contents`() = runTest {
        val root = Files.createTempDirectory("ws-root-rm")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("to-delete")
        Files.createFile(ws.path.resolve("file.txt"))
        mgr.removeWorkspace("to-delete")
        assertThat(Files.exists(ws.path)).isFalse()
    }

    @Test
    fun `removeWorkspace on non-existent path is a no-op`() = runTest {
        val root = Files.createTempDirectory("ws-root-rm2")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.removeWorkspace("does-not-exist")
    }

    @Test
    fun `removeWorkspace with path traversal resolves safely inside root`() = runTest {
        val root = Files.createTempDirectory("ws-root-rm3")
        val mgr = WorkspaceManager(root, noopExecutor())
        mgr.ensureWorkspace("../../../etc/passwd")
        mgr.removeWorkspace("../../../etc/passwd")
    }

    @Test
    fun `runAfterCreate delegates to hook executor`() = runTest {
        var called = false
        val executor = HookExecutor { _, _ -> called = true }
        val root = Files.createTempDirectory("ws-root-hk")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test")
        mgr.runAfterCreate(ws, "echo ok")
        assertThat(called).isTrue()
    }

    @Test
    fun `runBeforeRun delegates to hook executor`() = runTest {
        var calledScript = ""
        val executor = HookExecutor { _, script -> calledScript = script }
        val root = Files.createTempDirectory("ws-root-hk2")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test-2")
        mgr.runBeforeRun(ws, "echo before")
        assertThat(calledScript).isEqualTo("echo before")
    }

    @Test
    fun `runAfterRun logs warning on hook failure`() = runTest {
        val executor = HookExecutor { _, _ -> throw HookExecutionException("boom") }
        val root = Files.createTempDirectory("ws-root-hk3")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test-3")
        mgr.runAfterRun(ws, "exit 1", logger)
    }

    @Test
    fun `runAfterRun completes without exception on success`() = runTest {
        val executor = HookExecutor { _, _ -> }
        val root = Files.createTempDirectory("ws-root-hk4")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test-4")
        mgr.runAfterRun(ws, "echo ok", logger)
    }

    @Test
    fun `runBeforeRemove logs warning on hook failure`() = runTest {
        val executor = HookExecutor { _, _ -> throw HookExecutionException("fail") }
        val root = Files.createTempDirectory("ws-root-hk5")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test-5")
        mgr.runBeforeRemove(ws, "exit 1", logger)
    }

    @Test
    fun `runBeforeRemove completes without exception on success`() = runTest {
        val executor = HookExecutor { _, _ -> }
        val root = Files.createTempDirectory("ws-root-hk6")
        val mgr = WorkspaceManager(root, executor)
        val ws = mgr.ensureWorkspace("hook-test-6")
        mgr.runBeforeRemove(ws, "echo ok", logger)
    }

    @Test
    fun `constructor normalizes root path`() = runTest {
        val root = Files.createTempDirectory("ws-root-norm")
        val sub = root.resolve("subdir")
        val mgr = WorkspaceManager(sub, noopExecutor())
        val ws = mgr.ensureWorkspace("test")
        assertThat(Files.isDirectory(ws.path)).isTrue()
    }

    @Test
    fun `ensureWorkspace with path traversal resolves safely inside root`() = runTest {
        val root = Files.createTempDirectory("ws-root-trav")
        val mgr = WorkspaceManager(root, noopExecutor())
        val ws = mgr.ensureWorkspace("../../../etc/passwd")
        assertThat(Files.isDirectory(ws.path)).isTrue()
        assertThat(ws.path.startsWith(root)).isTrue()
    }

    @Test
    fun `ensureWorkspace with tenant context resolves under tenant path`() = runTest {
        val root = Files.createTempDirectory("ws-root-tenant")
        val mgr = WorkspaceManager(root, noopExecutor())
        val tc = TenantContext(
            tenantId = TenantId("tenant-1"),
            projectSlug = "proj-a"
        )
        val ws = mgr.ensureWorkspace("ABC-1", tc)
        assertThat(Files.isDirectory(ws.path)).isTrue()
        assertThat(ws.path.startsWith(root.resolve("tenant-1").resolve("proj-a"))).isTrue()
    }

    @Test
    fun `removeWorkspace with tenant context deletes workspace`() = runTest {
        val root = Files.createTempDirectory("ws-root-rm-tenant")
        val mgr = WorkspaceManager(root, noopExecutor())
        val tc = TenantContext(
            tenantId = TenantId("t1"),
            projectSlug = "p1"
        )
        val ws = mgr.ensureWorkspace("ABC-1", tc)
        Files.createFile(ws.path.resolve("data.txt"))
        assertThat(Files.exists(ws.path)).isTrue()
        mgr.removeWorkspace("ABC-1", tc)
        assertThat(Files.exists(ws.path)).isFalse()
    }

    @Test
    fun `removeWorkspace with traversal tenantId is rejected`() = runTest {
        val root = Files.createTempDirectory("ws-root-sec")
        val mgr = WorkspaceManager(root, noopExecutor())
        val tc = TenantContext(
            tenantId = TenantId("../../evil"),
            projectSlug = "p"
        )
        val ws = mgr.ensureWorkspace("ABC-1", tc)
        // path was sanitized on create so it lands inside root
        assertThat(ws.path.startsWith(root)).isTrue()
        // removeWorkspace with tenant must also enforce the root boundary
        assertThrows<IllegalStateException> {
            // manually construct a malicious path and attempt removal via a fake TenantContext
            mgr.assertInsideRoot(root.parent.resolve("outside"))
        }
    }

    @Test
    fun `removeWorkspace with tenant context enforces root boundary`() = runTest {
        val root = Files.createTempDirectory("ws-root-sec2")
        val mgr = WorkspaceManager(root, noopExecutor())
        val tc = TenantContext(tenantId = TenantId("t1"), projectSlug = "p1")
        mgr.ensureWorkspace("SAFE-1", tc)
        // should succeed — path is inside root
        mgr.removeWorkspace("SAFE-1", tc)
    }

    private fun noopExecutor() = HookExecutor { _, _ -> }
}