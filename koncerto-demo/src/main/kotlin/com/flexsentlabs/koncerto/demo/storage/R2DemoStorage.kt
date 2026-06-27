package com.flexsentlabs.koncerto.demo.storage

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoResult
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal typealias HttpSender = (HttpRequest) -> HttpResponse<String>

class R2DemoStorage(
    private val endpoint: String,
    private val accessKey: String,
    private val secretKey: String,
    private val bucketName: String,
    private val publicUrlBase: String,
    private val quotaLimitBytes: Long = 9L * 1024 * 1024 * 1024,
    private val presignedUrlTtlSeconds: Long = 604800, // 7 days (R2 max); set R2_PUBLIC_URL_BASE for permanent URLs
    private val region: String = "auto",
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val httpSender: HttpSender = { request ->
        httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    },
    private val clock: () -> Instant = { Instant.now() }
) : DemoStorage {

    override suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<DemoStorage.StorageResult> {
        return uploadWithTags(taskId, file, contentType, emptyMap())
    }

    override suspend fun uploadWithTags(
        taskId: String, file: File, contentType: String, tags: Map<String, String>
    ): DemoResult<DemoStorage.StorageResult> {
        return try {
            val storageKey = "demo-recordings/$taskId/${file.name}"
            val fileBytes = Files.readAllBytes(file.toPath())
            val contentSha256 = R2DemoStorageSupport.sha256Hex(fileBytes)

            val url = "$endpoint/$bucketName/$storageKey"
            val now = clock()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

            val headers = mutableMapOf(
                "content-type" to contentType,
                "x-amz-content-sha256" to contentSha256,
                "x-amz-date" to amzDate
            )
            val authHeader = R2DemoStorageSupport.buildSigV4AuthHeader(
                "PUT", "/$bucketName/$storageKey", "", headers, contentSha256, now,
                endpoint, accessKey, secretKey, region
            )
            headers["authorization"] = authHeader

            val builder = HttpRequest.newBuilder().uri(URI.create(url))
            headers.forEach { (key, value) ->
                if (key != "authorization") builder.header(key, value)
            }
            val request = builder.header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build()

            val response = httpSender(request)

            if (response.statusCode() in 200..299) {
                val publicUrl = if (publicUrlBase.isNotBlank()) {
                    "$publicUrlBase/$storageKey"
                } else {
                    R2DemoStorageSupport.generatePresignedUrl(
                        storageKey, presignedUrlTtlSeconds, endpoint, accessKey, secretKey, bucketName, region, now
                    )
                }
                DemoResult.Success(DemoStorage.StorageResult(
                    storageKey = storageKey,
                    url = publicUrl,
                    sizeBytes = fileBytes.size.toLong()
                ))
            } else {
                DemoResult.Failure(DemoError.StorageFailed(
                    RuntimeException("R2 upload failed: ${response.statusCode()} ${response.body()}")
                ))
            }
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    override suspend fun delete(storageKey: String): DemoResult<Unit> {
        return try {
            val url = "$endpoint/$bucketName/$storageKey"
            val now = clock()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
            val authHeader = R2DemoStorageSupport.buildSigV4AuthHeader(
                "DELETE", "/$bucketName/$storageKey", "", headers, emptyHash, now,
                endpoint, accessKey, secretKey, region
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", emptyHash)
                .header("Authorization", authHeader)
                .DELETE()
                .build()

            val response = httpSender(request)

            if (response.statusCode() in 200..299) {
                DemoResult.Success(Unit)
            } else {
                DemoResult.Failure(DemoError.StorageFailed(
                    RuntimeException("R2 delete failed: ${response.statusCode()}")
                ))
            }
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    override suspend fun generateUrl(storageKey: String, expiresInSeconds: Long): DemoResult<String> {
        return try {
            if (publicUrlBase.isNotBlank()) {
                return DemoResult.Success("$publicUrlBase/$storageKey")
            }
            val presigned = R2DemoStorageSupport.generatePresignedUrl(
                storageKey, expiresInSeconds, endpoint, accessKey, secretKey, bucketName, region, clock()
            )
            DemoResult.Success(presigned)
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    override suspend fun checkQuota(): DemoResult<DemoStorage.QuotaInfo> {
        return try {
            var totalSize = 0L
            var continuationToken: String? = null
            val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            do {
                val queryParts = mutableListOf("list-type=2")
                if (continuationToken != null) {
                    queryParts.add("continuation-token=${R2DemoStorageSupport.urlEncode(continuationToken)}")
                }
                val queryString = queryParts.joinToString("&")
                val url = "$endpoint/$bucketName/?$queryString"
                val now = clock()
                val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
                val authHeader = R2DemoStorageSupport.buildSigV4AuthHeader(
                    "GET", "/$bucketName/", queryString, headers, emptyHash, now,
                    endpoint, accessKey, secretKey, region
                )

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", emptyHash)
                    .header("Authorization", authHeader)
                    .GET()
                    .build()

                val response = httpSender(request)
                if (response.statusCode() !in 200..299) {
                    return DemoResult.Failure(DemoError.StorageFailed(
                        RuntimeException("R2 list failed: ${response.statusCode()} ${response.body()}")
                    ))
                }

                val page = R2DemoStorageSupport.parseListBucketPage(response.body())
                totalSize += page.totalSizeBytes
                continuationToken = page.continuationToken
            } while (continuationToken != null)

            DemoResult.Success(R2DemoStorageSupport.computeQuotaInfo(totalSize, quotaLimitBytes))
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    override suspend fun listOldest(limit: Int): DemoResult<List<DemoStorage.StorageItem>> {
        return try {
            val items = mutableListOf<DemoStorage.StorageItem>()
            var continuationToken: String? = null
            val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            do {
                val queryParts = mutableListOf("list-type=2")
                if (continuationToken != null) {
                    queryParts.add("continuation-token=${R2DemoStorageSupport.urlEncode(continuationToken)}")
                }
                val queryString = queryParts.joinToString("&")
                val url = "$endpoint/$bucketName/?$queryString"
                val now = clock()
                val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
                val authHeader = R2DemoStorageSupport.buildSigV4AuthHeader(
                    "GET", "/$bucketName/", queryString, headers, emptyHash, now,
                    endpoint, accessKey, secretKey, region
                )

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", emptyHash)
                    .header("Authorization", authHeader)
                    .GET()
                    .build()

                val response = httpSender(request)
                if (response.statusCode() !in 200..299) {
                    return DemoResult.Failure(DemoError.StorageFailed(
                        RuntimeException("R2 list failed: ${response.statusCode()} ${response.body()}")
                    ))
                }

                val page = R2DemoStorageSupport.parseListBucketPage(response.body())
                items.addAll(page.items)
                continuationToken = page.continuationToken
            } while (continuationToken != null)

            items.sortBy { it.lastModified }
            DemoResult.Success(items.take(limit))
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    override suspend fun deleteBatch(storageKeys: List<String>): DemoResult<Int> {
        return try {
            if (storageKeys.isEmpty()) return DemoResult.Success(0)

            val xmlBody = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<Delete>")
                for (key in storageKeys) {
                    append("<Object><Key>")
                    append(key.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    append("</Key></Object>")
                }
                append("</Delete>")
            }
            val bodyBytes = xmlBody.toByteArray()
            val contentSha256 = R2DemoStorageSupport.sha256Hex(xmlBody)
            val queryString = "delete"
            val url = "$endpoint/$bucketName/?$queryString"
            val now = clock()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            val headers = mapOf(
                "x-amz-content-sha256" to contentSha256,
                "x-amz-date" to amzDate
            )
            val authHeader = R2DemoStorageSupport.buildSigV4AuthHeader(
                "POST", "/$bucketName/", queryString, headers, contentSha256, now,
                endpoint, accessKey, secretKey, region
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/xml")
                .header("x-amz-content-sha256", contentSha256)
                .header("x-amz-date", amzDate)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build()

            val response = httpSender(request)

            if (response.statusCode() in 200..299) {
                DemoResult.Success(R2DemoStorageSupport.parseDeletedCount(response.body()))
            } else {
                DemoResult.Failure(DemoError.StorageFailed(
                    RuntimeException("R2 batch delete failed: ${response.statusCode()} ${response.body()}")
                ))
            }
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }
}
