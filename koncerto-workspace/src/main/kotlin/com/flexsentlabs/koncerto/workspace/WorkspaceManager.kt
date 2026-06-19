package com.flexsentlabs.koncerto.workspace

import com.flexsentlabs.koncerto.core.tenant.TenantContext
import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

data class Workspace(val path: Path, val workspaceKey: String, val createdNow: Boolean)

class WorkspaceManager(
    private val root: Path,
    private val hookExecutor: HookExecutor
) {
    private val absoluteRoot: Path = root.toAbsolutePath().normalize()

    fun ensureWorkspace(identifier: String, tenantContext: TenantContext? = null): Workspace {
        val key = WorkspaceKey.sanitize(identifier)
        val path = resolvePath(key, tenantContext)
        val createdNow = !Files.exists(path)
        if (createdNow) Files.createDirectories(path)
        return Workspace(path, key, createdNow)
    }

    private fun resolvePath(key: String, tenantContext: TenantContext?): Path {
        val base = if (tenantContext != null) {
            absoluteRoot.resolve(tenantContext.tenantId.value).resolve(tenantContext.projectSlug)
        } else {
            absoluteRoot
        }
        return base.resolve(key).toAbsolutePath().normalize()
    }

    fun assertInsideRoot(candidate: Path) {
        val norm = candidate.toAbsolutePath().normalize()
        if (!norm.startsWith(absoluteRoot)) {
            throw IllegalStateException("invalid_workspace_cwd: $norm not inside $absoluteRoot")
        }
    }

    suspend fun runAfterCreate(workspace: Workspace, script: String) {
        hookExecutor.run(workspace.path, script)
    }

    suspend fun runBeforeRun(workspace: Workspace, script: String) {
        hookExecutor.run(workspace.path, script)
    }

    suspend fun runAfterRun(workspace: Workspace, script: String, logger: StructuredLogger) {
        try { hookExecutor.run(workspace.path, script) }
        catch (e: Exception) {
            logger.warn("after_run_hook_failed", mapOf("workspace" to workspace.path.toString()),
                "error" to (e.message ?: "unknown"))
        }
    }

    suspend fun runBeforeRemove(workspace: Workspace, script: String, logger: StructuredLogger) {
        try { hookExecutor.run(workspace.path, script) }
        catch (e: Exception) {
            logger.warn("before_remove_hook_failed", mapOf("workspace" to workspace.path.toString()),
                "error" to (e.message ?: "unknown"))
        }
    }

    fun removeWorkspace(identifier: String) {
        val key = WorkspaceKey.sanitize(identifier)
        val path = absoluteRoot.resolve(key).toAbsolutePath().normalize()
        assertInsideRoot(path)
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    fun removeWorkspace(identifier: String, tenantContext: TenantContext) {
        val key = WorkspaceKey.sanitize(identifier)
        val path = resolvePath(key, tenantContext)
        assertInsideRoot(path)
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}