package dev.pyrlyn.s3buildcache

import com.sun.net.httpserver.HttpServer
import org.gradle.caching.BuildCacheEntryWriter
import org.gradle.caching.BuildCacheKey
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.AfterEach as AfterTest
import org.junit.jupiter.api.BeforeEach as BeforeTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertArrayEquals as assertContentEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/** Minimal in-memory path-style S3 server: GET/PUT/HEAD on /<bucket>/<key>. */
private class FakeS3 {
    val objects = ConcurrentHashMap<String, ByteArray>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val path = ex.requestURI.path.removePrefix("/")
            when (ex.requestMethod) {
                "PUT" -> {
                    val raw = ex.requestBody.readBytes()
                    val chunked = ex.requestHeaders.getFirst("x-amz-content-sha256")?.startsWith("STREAMING") == true ||
                        ex.requestHeaders.getFirst("Content-Encoding")?.contains("aws-chunked") == true
                    objects[path] = if (chunked) decodeAwsChunked(raw) else raw
                    ex.responseHeaders.add("ETag", "\"fake\"")
                    ex.sendResponseHeaders(200, -1)
                }
                "GET", "HEAD" -> {
                    val body = objects[path]
                    if (body == null) {
                        val err = "<?xml version=\"1.0\"?><Error><Code>NoSuchKey</Code><Message>missing</Message></Error>"
                            .toByteArray()
                        ex.responseHeaders.add("Content-Type", "application/xml")
                        if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(404, -1)
                        else { ex.sendResponseHeaders(404, err.size.toLong()); ex.responseBody.write(err) }
                    } else {
                        ex.responseHeaders.add("Content-Type", "application/octet-stream")
                        if (ex.requestMethod == "HEAD") ex.sendResponseHeaders(200, -1)
                        else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
                    }
                }
                else -> ex.sendResponseHeaders(405, -1)
            }
            ex.close()
        }
        start()
    }
    val endpoint get() = "http://127.0.0.1:${server.address.port}"
    fun stop() = server.stop(0)
}

/** Decodes SigV4 streaming (aws-chunked) bodies: `<hex>[;ext]\r\n<data>\r\n ... 0\r\n<trailers>`. */
private fun decodeAwsChunked(raw: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < raw.size) {
        var end = i
        while (!(raw[end] == '\r'.code.toByte() && raw[end + 1] == '\n'.code.toByte())) end++
        val size = String(raw, i, end - i).substringBefore(';').trim().toInt(16)
        if (size == 0) break
        out.write(raw, end + 2, size)
        i = end + 2 + size + 2
    }
    return out.toByteArray()
}

internal fun key(hash: String) = object : BuildCacheKey {
    override fun getHashCode() = hash
    override fun toByteArray() = hash.toByteArray()
    @Deprecated("deprecated in Gradle") override fun getDisplayName() = hash
}

internal fun writer(data: ByteArray) = object : BuildCacheEntryWriter {
    override fun writeTo(output: OutputStream) = output.write(data)
    override fun getSize() = data.size.toLong()
}

/** Roundtrips through the real SDK against an in-process fake S3 (no Docker). */
class FakeS3ServiceTest {
    private lateinit var s3: FakeS3
    private lateinit var client: S3Client

    @BeforeTest fun setUp() {
        s3 = FakeS3()
        client = S3Client.builder()
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .httpClient(ApacheHttpClient.create())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .forcePathStyle(true)
            .endpointOverride(URI.create(s3.endpoint))
            .build()
    }

    @AfterTest fun tearDown() { client.close(); s3.stop() }

    @Test fun `store then load roundtrip`() {
        val service = S3BuildCacheService(client, "bucket", "pfx/")
        val data = "hello cache".toByteArray()
        service.store(key("abc123"), writer(data))
        var loaded: ByteArray? = null
        assertTrue(service.load(key("abc123")) { loaded = it.readBytes() })
        assertContentEquals(data, loaded)
    }

    @Test fun `miss returns false`() {
        val service = S3BuildCacheService(client, "bucket", "pfx/")
        assertFalse(service.load(key("nope")) { error("must not be called") })
    }

    @Test fun `prefix is used in object key`() {
        val service = S3BuildCacheService(client, "bucket", "team/gradle/")
        service.store(key("deadbeef"), writer(byteArrayOf(1, 2, 3)))
        assertEquals(setOf("bucket/team/gradle/deadbeef"), s3.objects.keys)
    }

    @Test fun `pull disabled returns false`() {
        S3BuildCacheService(client, "bucket", "pfx/").store(key("k1"), writer(byteArrayOf(9)))
        val noPull = S3BuildCacheService(client, "bucket", "pfx/", S3CacheOptions(pull = false))
        assertFalse(noPull.load(key("k1")) { error("must not be called") })
    }
}
