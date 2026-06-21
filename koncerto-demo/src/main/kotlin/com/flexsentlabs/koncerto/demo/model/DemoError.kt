package com.flexsentlabs.koncerto.demo.model

sealed class DemoError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RecorderNotAvailable(platform: String) : DemoError("recorder_not_available: $platform")
    class RecordingFailed(cause: Throwable) : DemoError("recording_failed: ${cause.message}", cause)
    class StorageFailed(cause: Throwable) : DemoError("storage_failed: ${cause.message}", cause)
    class ReportFailed(cause: Throwable) : DemoError("report_failed: ${cause.message}", cause)
    class PreflightFailed(check: String) : DemoError("preflight_failed: $check")
    class TaskNotFound(taskId: String) : DemoError("task_not_found: $taskId")
    class QuotaExceeded(currentBytes: Long, limitBytes: Long) :
        DemoError("quota_exceeded: ${currentBytes / 1_000_000}MB / ${limitBytes / 1_000_000}MB")
    class InvalidConfig(message: String) : DemoError("invalid_config: $message")
    class IntegrityCheckFailed(reason: String) : DemoError("integrity_failed: $reason")
    class AiModelFailed(cause: Throwable) : DemoError("ai_model_failed: ${cause.message}", cause)
    class LinearApiError(cause: Throwable) : DemoError("linear_api_error: ${cause.message}", cause)
    class PartialRecovery(message: String) : DemoError("partial_recovery: $message")
}

sealed class DemoResult<out T> {
    data class Success<T>(val value: T) : DemoResult<T>()
    data class Failure(val error: DemoError) : DemoResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): DemoResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    inline fun onSuccess(block: (T) -> Unit): DemoResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (DemoError) -> Unit): DemoResult<T> {
        if (this is Failure) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): DemoError? = (this as? Failure)?.error
}

inline fun <T> runDemoCatching(block: () -> T): DemoResult<T> = try {
    DemoResult.Success(block())
} catch (e: DemoError) {
    DemoResult.Failure(e)
} catch (e: Exception) {
    DemoResult.Failure(DemoError.RecordingFailed(e))
}
