package com.flexsentlabs.koncerto.workspace

import com.flexsentlabs.koncerto.logging.StructuredLogger
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class HookExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface HookExecutor {
    suspend fun run(workspacePath: Path, script: String)
}

class ShellHookExecutor(
    private val timeoutMs: Long,
    private val logger: StructuredLogger
) : HookExecutor {

    override suspend fun run(workspacePath: Path, script: String) {
        val pb = ProcessBuilder("bash", "-lc", script)
            .directory(workspacePath.toFile())
            .redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            logger.warn("hook_timeout", mapOf("workspace" to workspacePath.toString()))
            throw HookExecutionException("hook_timeout")
        }
        val output = proc.inputStream.bufferedReader().readText()
        if (proc.exitValue() != 0) {
            throw HookExecutionException("hook_exit_${proc.exitValue()}: ${output.take(2000)}")
        }
    }
}