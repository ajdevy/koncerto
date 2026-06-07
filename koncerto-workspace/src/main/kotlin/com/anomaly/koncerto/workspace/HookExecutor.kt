package com.anomaly.koncerto.workspace

import com.anomaly.koncerto.logging.StructuredLogger
import java.nio.file.Path
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class HookExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

fun interface HookExecutor {
    suspend fun run(workspacePath: Path, script: String)
}

class ShellHookExecutor(
    private val timeoutMs: Long,
    private val logger: StructuredLogger
) : HookExecutor {

    override suspend fun run(workspacePath: Path, script: String) {
        try {
            withTimeout(timeoutMs) {
                val pb = ProcessBuilder("bash", "-lc", script)
                    .directory(workspacePath.toFile())
                    .redirectErrorStream(true)
                val proc = pb.start()
                val output = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit != 0) {
                    throw HookExecutionException("hook_exit_$exit: ${output.take(2000)}")
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("hook_timeout", mapOf("workspace" to workspacePath.toString()))
            throw HookExecutionException("hook_timeout", e)
        }
    }
}