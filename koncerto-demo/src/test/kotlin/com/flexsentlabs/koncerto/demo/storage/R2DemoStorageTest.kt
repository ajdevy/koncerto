package com.flexsentlabs.koncerto.demo.storage

import com.flexsentlabs.koncerto.demo.model.DemoResult
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.net.http.HttpResponse
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class R2DemoStorageTest {

    private val fixedInstant = Instant.parse("2026-06-27T12:00:00Z")
    private val endpoint = "https://abc123.r2.cloudflarestorage.com"
    private val accessKey = "test-access-key"
    private val secretKey = "test-secret-key"
    private val bucket = "demo-bucket"

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `buildSigV4AuthHeader produces deterministic signature with sorted headers`() {
        val headers = mapOf(
            "content-type" to "video/webm",
            "x-amz-content-sha256" to "abc123",
            "x-amz-date" to "20260627T120000Z"
        )
        val auth = R2DemoStorageSupport.buildSigV4AuthHeader(
            method = "PUT",
            canonicalUri = "/$bucket/demo-recordings/task/demo.webm",
            canonicalQueryString = "",
            headers = headers,
            payloadHash = "abc123",
            timestamp = fixedInstant,
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            region = "auto"
        )

        assert(auth.startsWith("AWS4-HMAC-SHA256 Credential=$accessKey/20260627/auto/s3/aws4_request"))
        assert(auth.contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"))
        assert(auth.contains("Signature="))
    }

    @Test
    fun `generatePresignedUrl includes required query params and signature`() {
        val url = R2DemoStorageSupport.generatePresignedUrl(
            storageKey = "demo-recordings/task-1/demo.webm",
            expiresInSeconds = 3600,
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            region = "auto",
            timestamp = fixedInstant
        )

        assert(url.startsWith("$endpoint/$bucket/demo-recordings/task-1/demo.webm?"))
        assert(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"))
        assert(url.contains("X-Amz-Expires=3600"))
        assert(url.contains("X-Amz-Signature="))
    }

    @Test
    fun `urlEncode follows S3 encoding rules`() {
        assert(R2DemoStorageSupport.urlEncode("hello world") == "hello%20world")
        assert(R2DemoStorageSupport.urlEncode("a*b~c") == "a%2Ab~c")
    }

    @Test
    fun `computeQuotaInfo clamps available bytes at zero`() {
        val under = R2DemoStorageSupport.computeQuotaInfo(usedBytes = 100L, limitBytes = 1000L)
        assert(under.usedBytes == 100L)
        assert(under.availableBytes == 900L)

        val over = R2DemoStorageSupport.computeQuotaInfo(usedBytes = 1500L, limitBytes = 1000L)
        assert(over.availableBytes == 0L)
    }

    @Test
    fun `parseListBucketPage extracts sizes items and continuation token`() {
        val xml = """
            <ListBucketResult>
              <Contents>
                <Key>demo-recordings/a/file.webm</Key>
                <Size>100</Size>
                <LastModified>2026-06-01T00:00:00.000Z</LastModified>
              </Contents>
              <Contents>
                <Key>demo-recordings/b/file.webm</Key>
                <Size>200</Size>
                <LastModified>2026-06-02T00:00:00.000Z</LastModified>
              </Contents>
              <IsTruncated>true</IsTruncated>
              <NextContinuationToken>token-abc</NextContinuationToken>
            </ListBucketResult>
        """.trimIndent()

        val page = R2DemoStorageSupport.parseListBucketPage(xml)

        assert(page.totalSizeBytes == 300L)
        assert(page.items.size == 2)
        assert(page.continuationToken == "token-abc")
    }

    @Test
    fun `generateUrl returns public URL when publicUrlBase is set`() = runTest {
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com"
        )

        val result = storage.generateUrl("demo-recordings/task/demo.webm", 3600)

        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == "https://cdn.example.com/demo-recordings/task/demo.webm")
    }

    @Test
    fun `generateUrl returns presigned URL when publicUrlBase is blank`() = runTest {
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "",
            clock = { fixedInstant }
        )

        val result = storage.generateUrl("demo-recordings/task/demo.webm", 3600)

        assert(result is DemoResult.Success)
        val url = (result as DemoResult.Success).value
        assert(url.contains("X-Amz-Signature="))
    }

    @Test
    fun `upload uses public URL on success when publicUrlBase is set`() = runTest {
        val file = File(tempDir, "demo.webm").apply { writeText("recording") }
        val response = mockk<HttpResponse<String>> {
            every { statusCode() } returns 200
            every { body() } returns ""
        }
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com",
            httpSender = { response },
            clock = { fixedInstant }
        )

        val result = storage.upload("task-1", file, "video/webm")

        assert(result is DemoResult.Success)
        val upload = (result as DemoResult.Success).value
        assert(upload.url == "https://cdn.example.com/demo-recordings/task-1/demo.webm")
        assert(upload.sizeBytes == file.length())
    }

    @Test
    fun `checkQuota aggregates list pages via mock HTTP`() = runTest {
        val page1 = """
            <ListBucketResult>
              <Contents><Key>a</Key><Size>500</Size><LastModified>2026-06-01T00:00:00.000Z</LastModified></Contents>
              <IsTruncated>true</IsTruncated>
              <NextContinuationToken>page2</NextContinuationToken>
            </ListBucketResult>
        """.trimIndent()
        val page2 = """
            <ListBucketResult>
              <Contents><Key>b</Key><Size>300</Size><LastModified>2026-06-02T00:00:00.000Z</LastModified></Contents>
              <IsTruncated>false</IsTruncated>
            </ListBucketResult>
        """.trimIndent()
        var callCount = 0
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com",
            quotaLimitBytes = 1000L,
            httpSender = {
                callCount++
                mockk {
                    every { statusCode() } returns 200
                    every { body() } returns if (callCount == 1) page1 else page2
                }
            },
            clock = { fixedInstant }
        )

        val result = storage.checkQuota()

        assert(result is DemoResult.Success)
        val quota = (result as DemoResult.Success).value
        assert(quota.usedBytes == 800L)
        assert(quota.availableBytes == 200L)
        assert(callCount == 2)
    }

    @Test
    fun `deleteBatch returns zero for empty key list`() = runTest {
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com"
        )

        val result = storage.deleteBatch(emptyList())

        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == 0)
    }

    @Test
    fun `deleteBatch parses deleted count from response`() = runTest {
        val responseXml = """
            <DeleteResult>
              <Deleted><Key>a</Key></Deleted>
              <Deleted><Key>b</Key></Deleted>
            </DeleteResult>
        """.trimIndent()
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com",
            httpSender = {
                mockk {
                    every { statusCode() } returns 200
                    every { body() } returns responseXml
                }
            },
            clock = { fixedInstant }
        )

        val result = storage.deleteBatch(listOf("a", "b"))

        assert(result is DemoResult.Success)
        assert((result as DemoResult.Success).value == 2)
    }

    @Test
    fun `delete succeeds with mock HTTP`() = runTest {
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com",
            httpSender = {
                mockk {
                    every { statusCode() } returns 204
                    every { body() } returns ""
                }
            },
            clock = { fixedInstant }
        )

        val result = storage.delete("demo-recordings/task/demo.webm")

        assert(result is DemoResult.Success)
    }

    @Test
    fun `listOldest sorts and limits results`() = runTest {
        val xml = """
            <ListBucketResult>
              <Contents>
                <Key>demo-recordings/old/file.webm</Key>
                <Size>100</Size>
                <LastModified>2026-06-01T00:00:00.000Z</LastModified>
              </Contents>
              <Contents>
                <Key>demo-recordings/new/file.webm</Key>
                <Size>200</Size>
                <LastModified>2026-06-03T00:00:00.000Z</LastModified>
              </Contents>
              <IsTruncated>false</IsTruncated>
            </ListBucketResult>
        """.trimIndent()
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "https://cdn.example.com",
            httpSender = {
                mockk {
                    every { statusCode() } returns 200
                    every { body() } returns xml
                }
            },
            clock = { fixedInstant }
        )

        val result = storage.listOldest(1)

        assert(result is DemoResult.Success)
        val items = (result as DemoResult.Success).value
        assert(items.size == 1)
        assert(items[0].storageKey.contains("old"))
    }

    @Test
    fun `upload returns failure when HTTP status is not success`() = runTest {
        val file = File(tempDir, "fail.webm").apply { writeText("data") }
        val storage = R2DemoStorage(
            endpoint = endpoint,
            accessKey = accessKey,
            secretKey = secretKey,
            bucketName = bucket,
            publicUrlBase = "",
            httpSender = {
                mockk {
                    every { statusCode() } returns 403
                    every { body() } returns "forbidden"
                }
            },
            clock = { fixedInstant }
        )

        val result = storage.upload("task-fail", file, "video/webm")

        assert(result is DemoResult.Failure)
    }
}
