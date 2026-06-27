package com.flexsentlabs.koncerto.demo.storage

import com.flexsentlabs.koncerto.demo.model.DemoError
import com.flexsentlabs.koncerto.demo.model.DemoResult
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.util.LinkedHashMap
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class R2DemoStorage(
    private val endpoint: String,
    private val accessKey: String,
    private val secretKey: String,
    private val bucketName: String,
    private val publicUrlBase: String,
    private val quotaLimitBytes: Long = 9L * 1024 * 1024 * 1024,
    private val presignedUrlTtlSeconds: Long = 604800, // 7 days (R2 max); set R2_PUBLIC_URL_BASE for permanent URLs
    private val region: String = "auto"
) : DemoStorage {

    private val client = HttpClient.newHttpClient()

    override suspend fun upload(taskId: String, file: File, contentType: String): DemoResult<DemoStorage.StorageResult> {
        return uploadWithTags(taskId, file, contentType, emptyMap())
    }

    override suspend fun uploadWithTags(
        taskId: String, file: File, contentType: String, tags: Map<String, String>
    ): DemoResult<DemoStorage.StorageResult> {
        return try {
            val storageKey = "demo-recordings/$taskId/${file.name}"
            val fileBytes = Files.readAllBytes(file.toPath())
            val contentSha256 = MessageDigest.getInstance("SHA-256").digest(fileBytes)
                .joinToString("") { "%02x".format(it) }

            val url = "$endpoint/$bucketName/$storageKey"
            val now = Instant.now()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

            val headers = mutableMapOf(
                "content-type" to contentType,
                "x-amz-content-sha256" to contentSha256,
                "x-amz-date" to amzDate
            )
            val authHeader = buildSigV4AuthHeader("PUT", "/$bucketName/$storageKey", "", headers, contentSha256, now)
            headers["authorization"] = authHeader

            val builder = HttpRequest.newBuilder().uri(URI.create(url))
            headers.forEach { (key, value) ->
                if (key != "authorization") builder.header(key, value)
            }
            val request = builder.header("Authorization", authHeader)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                val publicUrl = if (publicUrlBase.isNotBlank()) {
                    "$publicUrlBase/$storageKey"
                } else {
                    generatePresignedUrl(storageKey, presignedUrlTtlSeconds)
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
            val now = Instant.now()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

            val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
            val authHeader = buildSigV4AuthHeader("DELETE", "/$bucketName/$storageKey", "", headers, emptyHash, now)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("x-amz-date", amzDate)
                .header("x-amz-content-sha256", emptyHash)
                .header("Authorization", authHeader)
                .DELETE()
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

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
            val presigned = generatePresignedUrl(storageKey, expiresInSeconds)
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
                    queryParts.add("continuation-token=${urlEncode(continuationToken)}")
                }
                val queryString = queryParts.joinToString("&")
                val url = "$endpoint/$bucketName/?$queryString"
                val now = Instant.now()
                val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
                val authHeader = buildSigV4AuthHeader("GET", "/$bucketName/", queryString, headers, emptyHash, now)

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", emptyHash)
                    .header("Authorization", authHeader)
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    return DemoResult.Failure(DemoError.StorageFailed(
                        RuntimeException("R2 list failed: ${response.statusCode()} ${response.body()}")
                    ))
                }

                val xml = response.body()
                val factory = DocumentBuilderFactory.newInstance()
                val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
                val sizeNodes = doc.getElementsByTagName("Size")
                for (i in 0 until sizeNodes.length) {
                    totalSize += sizeNodes.item(i).textContent.toLong()
                }

                val isTruncated = doc.getElementsByTagName("IsTruncated")
                continuationToken = if (isTruncated.length > 0 && isTruncated.item(0).textContent == "true") {
                    val tokens = doc.getElementsByTagName("NextContinuationToken")
                    if (tokens.length > 0) tokens.item(0).textContent else null
                } else {
                    null
                }
            } while (continuationToken != null)

            DemoResult.Success(DemoStorage.QuotaInfo(
                usedBytes = totalSize,
                limitBytes = quotaLimitBytes,
                availableBytes = (quotaLimitBytes - totalSize).coerceAtLeast(0)
            ))
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
                    queryParts.add("continuation-token=${urlEncode(continuationToken)}")
                }
                val queryString = queryParts.joinToString("&")
                val url = "$endpoint/$bucketName/?$queryString"
                val now = Instant.now()
                val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
                val headers = mapOf("x-amz-date" to amzDate, "x-amz-content-sha256" to emptyHash)
                val authHeader = buildSigV4AuthHeader("GET", "/$bucketName/", queryString, headers, emptyHash, now)

                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("x-amz-date", amzDate)
                    .header("x-amz-content-sha256", emptyHash)
                    .header("Authorization", authHeader)
                    .GET()
                    .build()

                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !in 200..299) {
                    return DemoResult.Failure(DemoError.StorageFailed(
                        RuntimeException("R2 list failed: ${response.statusCode()} ${response.body()}")
                    ))
                }

                val xml = response.body()
                val factory = DocumentBuilderFactory.newInstance()
                val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
                val contents = doc.getElementsByTagName("Contents")
                for (i in 0 until contents.length) {
                    val node = contents.item(i)
                    val key = getChildText(node, "Key") ?: continue
                    val size = getChildText(node, "Size")?.toLongOrNull() ?: 0L
                    val lastModified = getChildText(node, "LastModified")
                    items.add(DemoStorage.StorageItem(key, size, lastModified))
                }

                val isTruncated = doc.getElementsByTagName("IsTruncated")
                continuationToken = if (isTruncated.length > 0 && isTruncated.item(0).textContent == "true") {
                    val tokens = doc.getElementsByTagName("NextContinuationToken")
                    if (tokens.length > 0) tokens.item(0).textContent else null
                } else {
                    null
                }
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
            val contentSha256 = sha256Hex(xmlBody)
            val queryString = "delete"
            val url = "$endpoint/$bucketName/?$queryString"
            val now = Instant.now()
            val amzDate = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            val headers = mapOf(
                "x-amz-content-sha256" to contentSha256,
                "x-amz-date" to amzDate
            )
            val authHeader = buildSigV4AuthHeader("POST", "/$bucketName/", queryString, headers, contentSha256, now)

            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/xml")
                .header("x-amz-content-sha256", contentSha256)
                .header("x-amz-date", amzDate)
                .header("Authorization", authHeader)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() in 200..299) {
                val xml = response.body()
                val factory = DocumentBuilderFactory.newInstance()
                val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
                val deleted = doc.getElementsByTagName("Deleted")
                DemoResult.Success(deleted.length)
            } else {
                DemoResult.Failure(DemoError.StorageFailed(
                    RuntimeException("R2 batch delete failed: ${response.statusCode()} ${response.body()}")
                ))
            }
        } catch (e: Exception) {
            DemoResult.Failure(DemoError.StorageFailed(e))
        }
    }

    private fun buildSigV4AuthHeader(
        method: String, canonicalUri: String, canonicalQueryString: String,
        headers: Map<String, String>, payloadHash: String, timestamp: Instant
    ): String {
        val dateStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val dateTimeStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val credentialScope = "$dateStr/$region/s3/aws4_request"
        val hostService = URI.create(endpoint).host

        val allHeaders = LinkedHashMap<String, String>()
        allHeaders["host"] = hostService
        headers.entries.sortedBy { it.key.lowercase() }.forEach { (k, v) ->
            allHeaders[k.lowercase()] = v
        }

        val canonicalHeaders = allHeaders.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}:${it.value}" } + "\n"
        val signedHeaders = allHeaders.keys.sorted().joinToString(";")

        val canonicalRequest = "$method\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
        val canonicalRequestHash = sha256Hex(canonicalRequest)
        val stringToSign = "AWS4-HMAC-SHA256\n$dateTimeStr\n$credentialScope\n$canonicalRequestHash"

        val dateKey = hmacSha256("AWS4$secretKey", dateStr)
        val regionKey = hmacSha256(dateKey, region)
        val serviceKey = hmacSha256(regionKey, "s3")
        val signingKey = hmacSha256(serviceKey, "aws4_request")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        return "AWS4-HMAC-SHA256 Credential=$accessKey/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"
    }

    private fun generatePresignedUrl(storageKey: String, expiresInSeconds: Long): String {
        val now = Instant.now()
        val dateStr = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val dateTimeStr = now.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val credentialScope = "$dateStr/$region/s3/aws4_request"
        val algorithm = "AWS4-HMAC-SHA256"

        val host = URI.create(endpoint).host
        val canonicalUri = "/$bucketName/$storageKey"

        val queryParams = linkedMapOf(
            "X-Amz-Algorithm" to algorithm,
            "X-Amz-Credential" to "$accessKey/$credentialScope",
            "X-Amz-Date" to dateTimeStr,
            "X-Amz-Expires" to expiresInSeconds.toString(),
            "X-Amz-SignedHeaders" to "host"
        )

        val canonicalQueryString = queryParams.entries.joinToString("&") { (k, v) ->
            "${urlEncode(k)}=${urlEncode(v)}"
        }

        val canonicalRequest = "GET\n$canonicalUri\n$canonicalQueryString\nhost:$host\n\nhost\nUNSIGNED-PAYLOAD"
        val canonicalRequestHash = sha256Hex(canonicalRequest)
        val stringToSign = "$algorithm\n$dateTimeStr\n$credentialScope\n$canonicalRequestHash"

        val dateKey = hmacSha256("AWS4$secretKey", dateStr)
        val regionKey = hmacSha256(dateKey, region)
        val serviceKey = hmacSha256(regionKey, "s3")
        val signingKey = hmacSha256(serviceKey, "aws4_request")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        return "$endpoint/$bucketName/$storageKey?$canonicalQueryString&X-Amz-Signature=$signature"
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun hmacSha256(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String {
        return hmacSha256(key, data).joinToString("") { "%02x".format(it) }
    }

    private fun getChildText(parent: org.w3c.dom.Node, tagName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            if (children.item(i).nodeName == tagName) {
                return children.item(i).textContent
            }
        }
        return null
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")
    }
}
