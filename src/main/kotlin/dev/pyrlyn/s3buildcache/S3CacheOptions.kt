package dev.pyrlyn.s3buildcache

import java.nio.file.Path

/** Runtime options for [S3BuildCacheService]; decoupled from Gradle config for testability. */
data class S3CacheOptions(
    val pull: Boolean = true,
    val push: Boolean = true,
    val multipartThresholdBytes: Long = 16L * S3BuildCache.MIB,
    val multipartPartSizeBytes: Long = 8L * S3BuildCache.MIB,
    val multipartChecksums: Boolean = true,
    val maxSizeBytes: Long? = null,
    val lruRefreshAfterMs: Long = 3_600_000,
    val tempDir: Path? = null,
) {
    init {
        require(multipartPartSizeBytes >= MIN_PART_SIZE) { "multipartPartSizeBytes must be >= 5 MiB" }
        require(multipartThresholdBytes >= multipartPartSizeBytes) {
            "multipartThresholdBytes must be >= multipartPartSizeBytes"
        }
        require(maxSizeBytes == null || maxSizeBytes > 0) { "maxSizeBytes must be positive" }
    }

    companion object {
        const val MIN_PART_SIZE: Long = 5L * 1024L * 1024L
    }
}
