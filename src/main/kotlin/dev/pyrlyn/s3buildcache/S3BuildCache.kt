package dev.pyrlyn.s3buildcache

import org.gradle.caching.configuration.AbstractBuildCache

/**
 * Configuration for the S3-compatible remote build cache.
 * Reads are controlled by [isPull], writes by [isPush] (inherited).
 */
abstract class S3BuildCache : AbstractBuildCache() {
    /** Custom endpoint, e.g. https://<account>.r2.cloudflarestorage.com. Null = AWS default. */
    var endpoint: String? = null
    var bucket: String? = null
    /** Region; "auto" for R2, any value (e.g. "us-east-1") for MinIO. */
    var region: String = "us-east-1"
    /** Static credentials; if null, the AWS default provider chain is used. */
    var accessKeyId: String? = null
    var secretAccessKey: String? = null
    /** Path-style addressing (required by MinIO, recommended for R2). */
    var pathStyle: Boolean = false
    /** Shared namespace for cache entries. Projects using the same bucket + prefix share entries. */
    var prefix: String = "gradle-cache/"
    /** Whether to read entries from the cache. */
    var isPull: Boolean = true

    // --- HTTP / client tuning ---
    var maxConnections: Int = 50
    var connectionTimeoutMs: Long = 10_000
    var socketTimeoutMs: Long = 60_000
    var connectionTimeToLiveMs: Long = 60_000
    var apiCallTimeoutMs: Long = 300_000
    var apiCallAttemptTimeoutMs: Long = 120_000

    /** "WHEN_SUPPORTED" (default) or "WHEN_REQUIRED" for older S3-compatible servers. */
    var requestChecksumCalculation: String = "WHEN_SUPPORTED"

    // --- Multipart upload ---
    var multipartThresholdBytes: Long = 16L * MIB
    /** Part size, at least 5 MiB (S3 minimum). */
    var multipartPartSizeBytes: Long = 8L * MIB

    // --- Optional eviction (approximate LRU) ---
    /** Maximum total size of objects under [prefix]; null = unlimited (no eviction). */
    var maxSizeBytes: Long? = null
    /** Convenience view of [maxSizeBytes] in MiB. */
    var maxSizeMb: Long?
        get() = maxSizeBytes?.div(MIB)
        set(value) { maxSizeBytes = value?.times(MIB) }
    /** On a hit, refresh LastModified only if the entry is older than this (limits copy cost). */
    var lruRefreshAfterMs: Long = 3_600_000

    companion object {
        const val MIB: Long = 1024L * 1024L
    }
}
