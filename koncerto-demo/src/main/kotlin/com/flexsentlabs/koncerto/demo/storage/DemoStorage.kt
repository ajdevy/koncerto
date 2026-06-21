package com.flexsentlabs.koncerto.demo.storage

import com.flexsentlabs.koncerto.demo.model.DemoResult
import java.io.File

interface DemoStorage {
    suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<StorageResult>
    suspend fun uploadWithTags(taskId: String, file: File, contentType: String, tags: Map<String, String>): DemoResult<StorageResult>
    suspend fun delete(storageKey: String): DemoResult<Unit>
    suspend fun generateUrl(storageKey: String, expiresInSeconds: Long): DemoResult<String>
    suspend fun checkQuota(): DemoResult<QuotaInfo>
    suspend fun listOldest(limit: Int): DemoResult<List<StorageItem>>
    suspend fun deleteBatch(storageKeys: List<String>): DemoResult<Int>

    data class StorageResult(
        val storageKey: String,
        val url: String,
        val sizeBytes: Long
    )

    data class QuotaInfo(
        val usedBytes: Long,
        val limitBytes: Long,
        val availableBytes: Long
    )

    data class StorageItem(
        val storageKey: String,
        val sizeBytes: Long,
        val lastModified: String?
    )
}
