package com.flexsentlabs.koncerto.demo.storage

import com.flexsentlabs.koncerto.demo.storage.DemoStorage
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

internal data class R2ListPage(
    val totalSizeBytes: Long,
    val items: List<DemoStorage.StorageItem>,
    val continuationToken: String?
)

internal object R2DemoStorageSupport {
    fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun hmacSha256(key: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    fun hmacSha256Hex(key: ByteArray, data: String): String =
        hmacSha256(key, data).joinToString("") { "%02x".format(it) }

    fun buildSigV4AuthHeader(
        method: String,
        canonicalUri: String,
        canonicalQueryString: String,
        headers: Map<String, String>,
        payloadHash: String,
        timestamp: Instant,
        endpoint: String,
        accessKey: String,
        secretKey: String,
        region: String
    ): String {
        val dateStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val dateTimeStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        val credentialScope = "$dateStr/$region/s3/aws4_request"
        val hostService = URI.create(endpoint).host

        val allHeaders = linkedMapOf<String, String>()
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

    fun generatePresignedUrl(
        storageKey: String,
        expiresInSeconds: Long,
        endpoint: String,
        accessKey: String,
        secretKey: String,
        bucketName: String,
        region: String,
        timestamp: Instant = Instant.now()
    ): String {
        val dateStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        val dateTimeStr = timestamp.atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
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

    fun computeQuotaInfo(usedBytes: Long, limitBytes: Long): DemoStorage.QuotaInfo =
        DemoStorage.QuotaInfo(
            usedBytes = usedBytes,
            limitBytes = limitBytes,
            availableBytes = (limitBytes - usedBytes).coerceAtLeast(0)
        )

    fun parseListBucketPage(xml: String): R2ListPage {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())

        var totalSize = 0L
        val sizeNodes = doc.getElementsByTagName("Size")
        for (i in 0 until sizeNodes.length) {
            totalSize += sizeNodes.item(i).textContent.toLong()
        }

        val items = mutableListOf<DemoStorage.StorageItem>()
        val contents = doc.getElementsByTagName("Contents")
        for (i in 0 until contents.length) {
            val node = contents.item(i)
            val key = getChildText(node, "Key") ?: continue
            val size = getChildText(node, "Size")?.toLongOrNull() ?: 0L
            val lastModified = getChildText(node, "LastModified")
            items.add(DemoStorage.StorageItem(key, size, lastModified))
        }

        val isTruncated = doc.getElementsByTagName("IsTruncated")
        val continuationToken = if (isTruncated.length > 0 && isTruncated.item(0).textContent == "true") {
            val tokens = doc.getElementsByTagName("NextContinuationToken")
            if (tokens.length > 0) tokens.item(0).textContent else null
        } else {
            null
        }

        return R2ListPage(totalSize, items, continuationToken)
    }

    fun parseDeletedCount(xml: String): Int {
        val factory = DocumentBuilderFactory.newInstance()
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        return doc.getElementsByTagName("Deleted").length
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
}
