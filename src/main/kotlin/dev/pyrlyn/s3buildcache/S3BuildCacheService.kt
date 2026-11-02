package dev.pyrlyn.s3buildcache

import org.gradle.api.logging.Logging
import org.gradle.caching.BuildCacheEntryReader
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheException
import org.gradle.caching.BuildCacheKey
import org.gradle.caching.BuildCacheService
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload
import software.amazon.awssdk.services.s3.model.CompletedPart
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.MetadataDirective
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.UploadPartRequest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Stores Gradle cache entries as S3 objects keyed purely by content: `<prefix><Gradle cache key hash>`.
 * No project or path components are part of the key, so projects sharing a bucket + prefix share entries.
 */
class S3BuildCacheService(
    private val client: S3Client,
    internal val bucket: String,
    internal val prefix: String,
    internal val options: S3CacheOptions = S3CacheOptions(),
) : BuildCacheService {

    private val log = Logging.getLogger(S3BuildCacheService::class.java)

    fun objectKey(key: BuildCacheKey): String = objectKey(prefix, key)

    override fun load(key: BuildCacheKey, reader: BuildCacheEntryReader): Boolean {
        if (!options.pull) return false
        val objectKey = objectKey(key)
        // Single GET, no HEAD: 404 means miss.
        val request = GetObjectRequest.builder().bucket(bucket).key(objectKey).build()
        val lastModified: Instant? = try {
            client.getObject(request).use { stream ->
                reader.readFrom(stream)
                stream.response().lastModified()
            }
        } catch (e: NoSuchKeyException) {
            return false
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) return false
            throw BuildCacheException("S3 load failed for $objectKey: ${e.message}", e)
        } catch (e: BuildCacheException) {
            throw e
        } catch (e: Exception) {
            throw BuildCacheException("S3 load failed for $objectKey: ${e.message}", e)
        }
        refreshIfStale(objectKey, lastModified)
        return true
    }

    /** Approximate LRU: bump LastModified via self-copy when eviction is on and the entry is stale. */
    internal fun refreshIfStale(objectKey: String, lastModified: Instant?, now: Instant = Instant.now()) {
        if (options.maxSizeBytes == null || lastModified == null) return
        if (lastModified.plusMillis(options.lruRefreshAfterMs).isAfter(now)) return
        try {
            client.copyObject(
                CopyObjectRequest.builder()
                    .sourceBucket(bucket).sourceKey(objectKey)
                    .destinationBucket(bucket).destinationKey(objectKey)
                    .metadataDirective(MetadataDirective.REPLACE)
                    .contentType(CONTENT_TYPE)
                    .build(),
            )
        } catch (e: Exception) {
            log.warn("S3 build cache: LRU refresh failed for {}: {}", objectKey, e.message)
        }
    }

    override fun store(key: BuildCacheKey, writer: BuildCacheEntryWriter) {
        if (!options.push) return
        val objectKey = objectKey(key)
        val size = writer.size
        try {
            if (size >= options.multipartThresholdBytes) {
                val tmp = Files.createTempFile(options.tempDir ?: defaultTempDir(), "s3-build-cache-", ".bin")
                try {
                    Files.newOutputStream(tmp).use { writer.writeTo(it) }
                    multipartUpload(objectKey, tmp)
                } finally {
                    Files.deleteIfExists(tmp)
                }
            } else {
                val buffer = ByteArrayOutputStream(size.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
                writer.writeTo(buffer)
                client.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(objectKey).contentType(CONTENT_TYPE).build(),
                    RequestBody.fromBytes(buffer.toByteArray()),
                )
            }
        } catch (e: BuildCacheException) {
            throw e
        } catch (e: Exception) {
            throw BuildCacheException("S3 store failed for $objectKey: ${e.message}", e)
        }
        if (options.maxSizeBytes != null) evictQuietly()
    }

    /** Streams [file] in parts of [S3CacheOptions.multipartPartSizeBytes]; aborts the upload on failure. */
    internal fun multipartUpload(objectKey: String, file: Path) {
        val create = CreateMultipartUploadRequest.builder().bucket(bucket).key(objectKey).contentType(CONTENT_TYPE)
        if (options.multipartChecksums) create.checksumAlgorithm(ChecksumAlgorithm.CRC32)
        val uploadId = client.createMultipartUpload(create.build()).uploadId()
        try {
            val parts = mutableListOf<CompletedPart>()
            val partSize = options.multipartPartSizeBytes.toInt()
            Files.newInputStream(file).use { input ->
                var partNumber = 1
                while (true) {
                    val chunk = readChunk(input, partSize)
                    if (chunk.isEmpty() && partNumber > 1) break
                    val req = UploadPartRequest.builder()
                        .bucket(bucket).key(objectKey).uploadId(uploadId)
                        .partNumber(partNumber).contentLength(chunk.size.toLong())
                    if (options.multipartChecksums) req.checksumAlgorithm(ChecksumAlgorithm.CRC32)
                    val resp = client.uploadPart(req.build(), RequestBody.fromBytes(chunk))
                    parts += CompletedPart.builder()
                        .partNumber(partNumber).eTag(resp.eTag())
                        .apply { if (options.multipartChecksums) checksumCRC32(resp.checksumCRC32()) }
                        .build()
                    partNumber++
                    if (chunk.size < partSize) break
                }
            }
            client.completeMultipartUpload(
                CompleteMultipartUploadRequest.builder()
                    .bucket(bucket).key(objectKey).uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
                    .build(),
            )
        } catch (e: Exception) {
            try {
                client.abortMultipartUpload(
                    AbortMultipartUploadRequest.builder().bucket(bucket).key(objectKey).uploadId(uploadId).build(),
                )
            } catch (abort: Exception) {
                e.addSuppressed(abort)
            }
            throw e
        }
    }

    /** Lists objects under the prefix, then deletes the oldest until under the limit. Never throws. */
    internal fun evictQuietly() {
        val max = options.maxSizeBytes ?: return
        try {
            val victims = selectForEviction(listAll(), max)
            victims.chunked(1000).forEach { batch ->
                client.deleteObjects(
                    DeleteObjectsRequest.builder().bucket(bucket)
                        .delete(
                            Delete.builder().quiet(true)
                                .objects(batch.map { ObjectIdentifier.builder().key(it).build() })
                                .build(),
                        ).build(),
                )
            }
            if (victims.isNotEmpty()) log.info("S3 build cache: evicted {} entries under {}", victims.size, prefix)
        } catch (e: Exception) {
            log.warn("S3 build cache: eviction failed under {}: {}", prefix, e.message)
        }
    }

    internal fun listAll(): List<CacheObject> {
        val result = mutableListOf<CacheObject>()
        var token: String? = null
        do {
            val resp = client.listObjectsV2(
                ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).continuationToken(token).build(),
            )
            resp.contents().forEach { result += CacheObject(it.key(), it.size(), it.lastModified()) }
            token = if (resp.isTruncated == true) resp.nextContinuationToken() else null
        } while (token != null)
        return result
    }

    override fun close() = client.close()

    companion object {
        const val CONTENT_TYPE = "application/vnd.gradle.build-cache-artifact.v2"

        /** Pure content-addressed key: prefix + Gradle key hash only. */
        fun objectKey(prefix: String, key: BuildCacheKey): String = prefix + key.hashCode

        private fun defaultTempDir(): Path = Path.of(System.getProperty("java.io.tmpdir"))

        private fun readChunk(input: InputStream, size: Int): ByteArray {
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) break
                read += n
            }
            return if (read == size) buf else buf.copyOf(read)
        }
    }
}
