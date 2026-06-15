package com.anomaly.koncerto.agent

import com.anomaly.koncerto.core.result.Result
import com.anomaly.koncerto.logging.StructuredLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelExhaustedException(
    val modelsTried: List<String>,
    val totalRetries: Int,
    val lastError: String? = null
) : Exception("All free models exhausted after $totalRetries total retries across ${modelsTried.size} models: ${modelsTried.joinToString(", ")}")

class FreeModelCycler(
    private val models: List<String>,
    private val maxRetriesPerModel: Int = 3,
    private val logger: StructuredLogger
) {
    init {
        require(models.isNotEmpty()) { "At least one free model required" }
        require(maxRetriesPerModel > 0) { "maxRetriesPerModel must be positive" }
    }

    private val mutex = Mutex()
    private var currentIndex = 0
    private val retryCounts = mutableMapOf<String, Int>()
    private var exhausted = false

    suspend fun nextModel(): Result<String, ModelExhaustedException> = mutex.withLock {
        if (exhausted) {
            return@withLock Result.Failure(ModelExhaustedException(
                modelsTried = models,
                totalRetries = retryCounts.values.sum(),
                lastError = "Already exhausted"
            ))
        } else {
            var attempts = 0
            while (attempts < models.size) {
                val model = models[currentIndex]
                val retries = retryCounts[model] ?: 0

                if (retries < maxRetriesPerModel) {
                    logger.debug("free_model_selected", mapOf(
                        "model" to model,
                        "attempt" to (retries + 1).toString(),
                        "max_retries" to maxRetriesPerModel.toString()
                    ))
                    return@withLock Result.Success(model)
                } else {
                    currentIndex = (currentIndex + 1) % models.size
                    attempts++
                }
            }

            exhausted = true
            val totalRetries = retryCounts.values.sum()
            logger.warn("free_models_exhausted", mapOf(
                "models_tried" to models.joinToString(","),
                "total_retries" to totalRetries.toString(),
                "max_retries_per_model" to maxRetriesPerModel.toString()
            ))

            Result.Failure(ModelExhaustedException(
                modelsTried = models.toList(),
                totalRetries = totalRetries
            ))
        }
    }

    suspend fun reportFailure(model: String, error: String? = null) = mutex.withLock {
        val count = (retryCounts[model] ?: 0) + 1
        retryCounts[model] = count
        logger.debug("free_model_failure", mapOf(
            "model" to model,
            "retry_count" to count.toString(),
            "max_retries" to maxRetriesPerModel.toString(),
            "error" to (error ?: "unknown")
        ))
    }

    suspend fun reportSuccess(model: String) = mutex.withLock {
        retryCounts[model] = 0
        logger.debug("free_model_success", mapOf("model" to model))
    }

    suspend fun reset() = mutex.withLock {
        currentIndex = 0
        retryCounts.clear()
        exhausted = false
        logger.info("free_model_cycler_reset", emptyMap())
    }

    suspend fun getStatus(): Map<String, Any> = mutex.withLock {
        mapOf(
            "current_index" to currentIndex,
            "models" to models,
            "retry_counts" to retryCounts.toMap(),
            "exhausted" to exhausted,
            "max_retries_per_model" to maxRetriesPerModel
        )
    }

    companion object {
        val DEFAULT_FREE_MODELS = listOf(
            "opencode-free-1",
            "opencode-free-2",
            "opencode-free-3"
        )

        fun createDefault(logger: StructuredLogger, maxRetriesPerModel: Int = 3): FreeModelCycler {
            return FreeModelCycler(DEFAULT_FREE_MODELS, maxRetriesPerModel, logger)
        }
    }
}
