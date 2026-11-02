package dev.pyrlyn.s3buildcache

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.gradle.caching.BuildCacheException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.S3Object
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import software.amazon.awssdk.services.s3.model.UploadPartResponse
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.time.Instant

class S3BuildCacheServiceMockTest {
    private val client = mockk<S3Client>(relaxed = true)
    private val mib = S3BuildCache.MIB

    private fun getResponse(data: ByteArray, lastModified: Instant = Instant.now()) = ResponseInputStream(
        GetObjectResponse.builder().lastModified(lastModified).contentLength(data.size.toLong()).build(),
        AbortableInputStream.create(ByteArrayInputStream(data)),
    )

    @Test fun `key is prefix plus hash only`() {
        val a = S3BuildCacheService(client, "b", "shared/")
        assertEquals("shared/0123abcd", a.objectKey(key("0123abcd")))
    }

    @Test fun `different projects with the same prefix produce the same key`() {
        // The service has no notion of a project; two independent instances (e.g. two projects) must agree.
        val projectA = S3BuildCacheService(mockk(relaxed = true), "bucket", "shared/")
        val projectB = S3BuildCacheService(mockk(relaxed = true), "bucket", "shared/")
        val k = key("feedface")
        assertEquals(projectA.objectKey(k), projectB.objectKey(k))
        assertEquals("shared/feedface", projectA.objectKey(k))
        assertFalse(projectA.objectKey(k).contains("project"))
    }

    @Test fun `load hit reads body with a single GET and no HEAD`() {
        val data = byteArrayOf(1, 2, 3)
        val req = slot<GetObjectRequest>()
        every { client.getObject(capture(req)) } returns getResponse(data)
        var got: ByteArray? = null
        assertTrue(S3BuildCacheService(client, "b", "p/").load(key("h")) { got = it.readBytes() })
        assertArrayEquals(data, got)
        assertEquals("p/h", req.captured.key())
        assertEquals("b", req.captured.bucket())
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
    }

    @Test fun `load miss via NoSuchKey returns false`() {
        every { client.getObject(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().statusCode(404).build()
        assertFalse(S3BuildCacheService(client, "b", "p/").load(key("h")) { error("unexpected") })
    }

    @Test fun `load miss via generic 404 returns false`() {
        every { client.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(404).build()
        assertFalse(S3BuildCacheService(client, "b", "p/").load(key("h")) { error("unexpected") })
    }

    @Test fun `load error throws BuildCacheException`() {
        every { client.getObject(any<GetObjectRequest>()) } throws S3Exception.builder().statusCode(403).message("denied").build()
        assertThrows<BuildCacheException> { S3BuildCacheService(client, "b", "p/").load(key("h")) {} }
    }

    @Test fun `pull disabled never calls S3`() {
        assertFalse(S3BuildCacheService(client, "b", "p/", S3CacheOptions(pull = false)).load(key("h")) {})
        verify(exactly = 0) { client.getObject(any<GetObjectRequest>()) }
    }

    @Test fun `push disabled never calls S3`() {
        S3BuildCacheService(client, "b", "p/", S3CacheOptions(push = false)).store(key("h"), writer(byteArrayOf(1)))
        verify(exactly = 0) { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
    }

    @Test fun `small store uses single PUT without HEAD`() {
        val req = slot<PutObjectRequest>()
        every { client.putObject(capture(req), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        S3BuildCacheService(client, "b", "p/").store(key("h"), writer(ByteArray(10)))
        assertEquals("p/h", req.captured.key())
        verify(exactly = 0) { client.headObject(any<HeadObjectRequest>()) }
        verify(exactly = 0) { client.createMultipartUpload(any<CreateMultipartUploadRequest>()) }
    }

    @Test fun `store error throws BuildCacheException`() {
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } throws S3Exception.builder().statusCode(500).build()
        assertThrows<BuildCacheException> { S3BuildCacheService(client, "b", "p/").store(key("h"), writer(ByteArray(1))) }
    }

    @Test fun `large store uses multipart with correct parts`(@TempDir tmp: Path) {
        every { client.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("u1").build()
        val parts = mutableListOf<UploadPartRequest>()
        every { client.uploadPart(capture(parts), any<RequestBody>()) } answers {
            UploadPartResponse.builder().eTag("e${parts.last().partNumber()}").checksumCRC32("c").build()
        }
        val complete = slot<CompleteMultipartUploadRequest>()
        every { client.completeMultipartUpload(capture(complete)) } returns CompleteMultipartUploadResponse.builder().build()

        val opts = S3CacheOptions(multipartThresholdBytes = 5 * mib, multipartPartSizeBytes = 5 * mib, tempDir = tmp)
        S3BuildCacheService(client, "b", "p/", opts).store(key("big"), writer(ByteArray((12 * mib).toInt())))

        assertEquals(listOf(1, 2, 3), parts.map { it.partNumber() })
        assertEquals(listOf(5 * mib, 5 * mib, 2 * mib), parts.map { it.contentLength() })
        assertEquals(listOf("e1", "e2", "e3"), complete.captured.multipartUpload().parts().map { it.eTag() })
        assertEquals("u1", complete.captured.uploadId())
        verify(exactly = 0) { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) }
        assertEquals(0, tmp.toFile().listFiles()!!.size, "temp file must be deleted")
    }

    @Test fun `multipart failure aborts upload and throws`(@TempDir tmp: Path) {
        every { client.createMultipartUpload(any<CreateMultipartUploadRequest>()) } returns
            CreateMultipartUploadResponse.builder().uploadId("u2").build()
        every { client.uploadPart(any<UploadPartRequest>(), any<RequestBody>()) } throws S3Exception.builder().statusCode(500).build()
        val abort = slot<AbortMultipartUploadRequest>()
        every { client.abortMultipartUpload(capture(abort)) } returns mockk(relaxed = true)
        val opts = S3CacheOptions(multipartThresholdBytes = 5 * mib, multipartPartSizeBytes = 5 * mib, tempDir = tmp)
        assertThrows<BuildCacheException> {
            S3BuildCacheService(client, "b", "p/", opts).store(key("big"), writer(ByteArray((6 * mib).toInt())))
        }
        assertEquals("u2", abort.captured.uploadId())
    }

    @Test fun `eviction lists with pagination and deletes oldest`() {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val page1 = ListObjectsV2Response.builder().isTruncated(true).nextContinuationToken("t")
            .contents(S3Object.builder().key("p/a").size(40).lastModified(now.minusSeconds(300)).build()).build()
        val page2 = ListObjectsV2Response.builder().isTruncated(false)
            .contents(
                S3Object.builder().key("p/b").size(40).lastModified(now.minusSeconds(200)).build(),
                S3Object.builder().key("p/c").size(40).lastModified(now.minusSeconds(100)).build(),
            ).build()
        val lists = mutableListOf<ListObjectsV2Request>()
        every { client.listObjectsV2(capture(lists)) } answers { if (lists.last().continuationToken() == null) page1 else page2 }
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        val del = slot<DeleteObjectsRequest>()
        every { client.deleteObjects(capture(del)) } returns DeleteObjectsResponse.builder().build()

        S3BuildCacheService(client, "b", "p/", S3CacheOptions(maxSizeBytes = 80)).store(key("x"), writer(ByteArray(1)))

        assertEquals(listOf(null, "t"), lists.map { it.continuationToken() })
        assertEquals("p/", lists.first().prefix())
        assertEquals(listOf("p/a"), del.captured.delete().objects().map { it.key() })
    }

    @Test fun `eviction failure does not fail store`() {
        every { client.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns PutObjectResponse.builder().build()
        every { client.listObjectsV2(any<ListObjectsV2Request>()) } throws S3Exception.builder().statusCode(500).build()
        S3BuildCacheService(client, "b", "p/", S3CacheOptions(maxSizeBytes = 1)).store(key("x"), writer(ByteArray(1)))
    }

    @Test fun `no eviction when unlimited`() {
        S3BuildCacheService(client, "b", "p/").store(key("x"), writer(ByteArray(1)))
        verify(exactly = 0) { client.listObjectsV2(any<ListObjectsV2Request>()) }
    }

    @Test fun `hit on stale entry refreshes LastModified only when eviction enabled`() {
        every { client.getObject(any<GetObjectRequest>()) } answers { getResponse(byteArrayOf(1), Instant.now().minusSeconds(7200)) }
        S3BuildCacheService(client, "b", "p/").load(key("h")) { it.readBytes() }
        verify(exactly = 0) { client.copyObject(any<CopyObjectRequest>()) }

        val copy = slot<CopyObjectRequest>()
        every { client.copyObject(capture(copy)) } returns mockk(relaxed = true)
        S3BuildCacheService(client, "b", "p/", S3CacheOptions(maxSizeBytes = 100)).load(key("h")) { it.readBytes() }
        assertEquals("p/h", copy.captured.destinationKey())
        assertEquals("p/h", copy.captured.sourceKey())
    }

    @Test fun `hit on fresh entry does not refresh`() {
        every { client.getObject(any<GetObjectRequest>()) } answers { getResponse(byteArrayOf(1), Instant.now()) }
        S3BuildCacheService(client, "b", "p/", S3CacheOptions(maxSizeBytes = 100)).load(key("h")) { it.readBytes() }
        verify(exactly = 0) { client.copyObject(any<CopyObjectRequest>()) }
    }

    @Test fun `refresh failure is swallowed`() {
        every { client.getObject(any<GetObjectRequest>()) } answers { getResponse(byteArrayOf(1), Instant.EPOCH) }
        every { client.copyObject(any<CopyObjectRequest>()) } throws S3Exception.builder().statusCode(500).build()
        assertTrue(S3BuildCacheService(client, "b", "p/", S3CacheOptions(maxSizeBytes = 100)).load(key("h")) { it.readBytes() })
    }
}
