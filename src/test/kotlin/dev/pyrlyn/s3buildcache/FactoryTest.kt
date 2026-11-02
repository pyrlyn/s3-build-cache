package dev.pyrlyn.s3buildcache

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.gradle.caching.BuildCacheServiceFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation

class FactoryTest {
    private fun config(block: S3BuildCache.() -> Unit = {}) = object : S3BuildCache() {}.apply(block)

    private fun describer(): BuildCacheServiceFactory.Describer {
        val d = mockk<BuildCacheServiceFactory.Describer>()
        every { d.type(any()) } returns d
        every { d.config(any(), any()) } returns d
        return d
    }

    @Test fun `defaults`() {
        val c = config()
        assertEquals("us-east-1", c.region)
        assertEquals("gradle-cache/", c.prefix)
        assertEquals(true, c.isPull)
        assertFalse(c.pathStyle)
        assertNull(c.maxSizeBytes)
        assertEquals(50, c.maxConnections)
        assertEquals(16 * S3BuildCache.MIB, c.multipartThresholdBytes)
        assertEquals(8 * S3BuildCache.MIB, c.multipartPartSizeBytes)
    }

    @Test fun `maxSizeMb maps to bytes`() {
        val c = config { maxSizeMb = 3 }
        assertEquals(3 * S3BuildCache.MIB, c.maxSizeBytes)
        assertEquals(3L, c.maxSizeMb)
    }

    @Test fun `factory builds service from config and describes it`() {
        val d = describer()
        val c = config {
            endpoint = "http://127.0.0.1:9"; bucket = "bkt"; region = "auto"; pathStyle = true
            accessKeyId = "id"; secretAccessKey = "secret"; prefix = "team/"
            isPull = false; isPush = true; maxSizeMb = 10; requestChecksumCalculation = "when_required"
        }
        val service = S3BuildCacheServiceFactory().createBuildCacheService(c, d) as S3BuildCacheService
        service.use {
            assertEquals("bkt", it.bucket)
            assertEquals("team/", it.prefix)
            assertFalse(it.options.pull)
            assertEquals(true, it.options.push)
            assertFalse(it.options.multipartChecksums)
            assertEquals(10 * S3BuildCache.MIB, it.options.maxSizeBytes)
        }
        verify { d.type("S3") }
        verify { d.config("bucket", "bkt") }
        verify { d.config("requestChecksumCalculation", "WHEN_REQUIRED") }
        verify(exactly = 0) { d.config(any(), "secret") }
    }

    @Test fun `missing bucket fails`() {
        assertThrows<IllegalArgumentException> { S3BuildCacheServiceFactory().createBuildCacheService(config(), describer()) }
    }

    @Test fun `checksum parsing`() {
        assertEquals(RequestChecksumCalculation.WHEN_SUPPORTED, S3BuildCacheServiceFactory.parseChecksumCalculation("WHEN_SUPPORTED"))
        assertEquals(RequestChecksumCalculation.WHEN_REQUIRED, S3BuildCacheServiceFactory.parseChecksumCalculation(" when_required "))
        assertThrows<IllegalArgumentException> { S3BuildCacheServiceFactory.parseChecksumCalculation("ALWAYS") }
    }

    @Test fun `part size below 5 MiB rejected`() {
        assertThrows<IllegalArgumentException> { S3CacheOptions(multipartPartSizeBytes = 1024) }
    }
}
