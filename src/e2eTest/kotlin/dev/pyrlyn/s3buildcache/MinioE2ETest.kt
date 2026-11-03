package dev.pyrlyn.s3buildcache

import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import java.io.OutputStream
import java.util.UUID
import kotlin.random.Random

/** End-to-end tests against a real MinIO in Docker. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioE2ETest {
    // Official minio/minio images are no longer pullable from Docker Hub; default to Chainguard's build.
    // Override with -De2e.minioImage=... or MINIO_IMAGE.
    private val minioImage = System.getProperty("e2e.minioImage") ?: System.getenv("MINIO_IMAGE") ?: "cgr.dev/chainguard/minio:latest"
    private val minio = MinIOContainer(DockerImageName.parse(minioImage).asCompatibleSubstituteFor("minio/minio"))

    private val bucket = "gradle-cache"
    private val mib = S3BuildCache.MIB

    private fun config(block: S3BuildCache.() -> Unit = {}) = object : S3BuildCache() {}.apply {
        endpoint = minio.s3URL
        this.bucket = this@MinioE2ETest.bucket
        region = "us-east-1"
        pathStyle = true
        accessKeyId = minio.userName
        secretAccessKey = minio.password
        isPush = true
        block()
    }

    /** Builds a service exactly as Gradle would, through the factory. */
    private fun service(block: S3BuildCache.() -> Unit = {}): S3BuildCacheService {
        val describer = object : org.gradle.caching.BuildCacheServiceFactory.Describer {
            override fun type(type: String) = this
            override fun config(name: String, value: String) = this
        }
        return S3BuildCacheServiceFactory().createBuildCacheService(config(block), describer) as S3BuildCacheService
    }

    private fun key(hash: String) = object : BuildCacheKey {
        override fun getHashCode() = hash
        override fun toByteArray() = hash.toByteArray()
    }

    private fun writer(data: ByteArray) = object : BuildCacheEntryWriter {
        override fun writeTo(output: OutputStream) = output.write(data)
        override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(data)
        override fun getSize() = data.size.toLong()
    }

    private fun load(s: S3BuildCacheService, k: String): ByteArray? {
        var out: ByteArray? = null
        return if (s.load(key(k)) { out = it.readBytes() }) out else null
    }

    private fun rawClient(checksum: String = "WHEN_SUPPORTED") =
        S3BuildCacheServiceFactory.buildClient(config(), S3BuildCacheServiceFactory.parseChecksumCalculation(checksum))

    @BeforeAll fun createBucket() {
        minio.start()
        rawClient().use { it.createBucket(CreateBucketRequest.builder().bucket(bucket).build()) }
    }

    private fun uniqueHash() = UUID.randomUUID().toString().replace("-", "")

    @Test fun `put then get roundtrip and head sees object`() {
        val data = Random.nextBytes(4096)
        val h = uniqueHash()
        service { prefix = "rt/" }.use { s ->
            s.store(key(h), writer(data))
            assertArrayEquals(data, load(s, h))
        }
        rawClient().use { c ->
            val head = c.headObject(HeadObjectRequest.builder().bucket(bucket).key("rt/$h").build())
            assertEquals(4096L, head.contentLength())
        }
    }

    @Test fun `miss returns false`() {
        service { prefix = "miss/" }.use { assertFalse(it.load(key(uniqueHash())) { error("unexpected") }) }
    }

    @Test fun `entry stored by one instance is loaded by an independent instance`() {
        val data = Random.nextBytes(1000)
        val h = uniqueHash()
        service { prefix = "shared/" }.use { projectA -> projectA.store(key(h), writer(data)) }
        service { prefix = "shared/" }.use { projectB -> assertArrayEquals(data, load(projectB, h)) }
    }

    @Test fun `multipart upload above threshold roundtrips`() {
        val data = Random.nextBytes((13 * mib).toInt())
        val h = uniqueHash()
        service { prefix = "mp/"; multipartThresholdBytes = 6 * mib; multipartPartSizeBytes = 5 * mib }.use { s ->
            s.store(key(h), writer(data))
            assertArrayEquals(data, load(s, h))
        }
    }

    @Test fun `multipart and single put work with WHEN_REQUIRED checksums`() {
        val big = Random.nextBytes((11 * mib).toInt())
        val small = Random.nextBytes(100)
        val hb = uniqueHash()
        val hs = uniqueHash()
        service {
            prefix = "req/"; requestChecksumCalculation = "WHEN_REQUIRED"
            multipartThresholdBytes = 5 * mib; multipartPartSizeBytes = 5 * mib
        }.use { s ->
            s.store(key(hb), writer(big))
            s.store(key(hs), writer(small))
            assertArrayEquals(big, load(s, hb))
            assertArrayEquals(small, load(s, hs))
        }
    }

    @Test fun `eviction keeps prefix under limit and refreshes on hit`() {
        val p = "ev/"
        service { prefix = p; maxSizeBytes = 2500; lruRefreshAfterMs = 0 }.use { s ->
            val hashes = (1..5).map { uniqueHash() }
            hashes.forEach { h ->
                s.store(key(h), writer(Random.nextBytes(1000)))
                Thread.sleep(1100) // LastModified has 1s resolution
            }
            rawClient().use { c ->
                val objects = c.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(p).build()).contents()
                assertTrue(objects.sumOf { it.size() } <= 2500, "total ${objects.sumOf { it.size() }}")
                assertEquals(setOf("$p${hashes[3]}", "$p${hashes[4]}"), objects.map { it.key() }.toSet())
            }
            // Refresh the older survivor, then add one more: the other (now oldest) must be evicted.
            assertTrue(load(s, hashes[3]) != null)
            Thread.sleep(1100)
            val h6 = uniqueHash()
            s.store(key(h6), writer(Random.nextBytes(1000)))
            rawClient().use { c ->
                val keys = c.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(p).build())
                    .contents().map { it.key() }.toSet()
                assertEquals(setOf("$p${hashes[3]}", "$p$h6"), keys)
            }
        }
    }

    @AfterAll fun done() = minio.stop()
}
