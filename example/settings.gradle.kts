import dev.pyrlyn.s3buildcache.S3BuildCache

pluginManagement {
    includeBuild("..")
}

plugins {
    id("dev.pyrlyn.s3-build-cache")
}

val isCi = System.getenv("CI") != null

buildCache {
    local { isEnabled = !isCi }
    remote<S3BuildCache> {
        endpoint = System.getenv("S3_CACHE_ENDPOINT")   // null = AWS
        bucket = System.getenv("S3_CACHE_BUCKET") ?: "gradle-cache"
        region = System.getenv("S3_CACHE_REGION") ?: "us-east-1"
        accessKeyId = System.getenv("S3_CACHE_ACCESS_KEY_ID")
        secretAccessKey = System.getenv("S3_CACHE_SECRET_ACCESS_KEY")
        pathStyle = System.getenv("S3_CACHE_PATH_STYLE")?.toBoolean() ?: false
        prefix = "shared/"                              // same bucket+prefix = shared cache
        maxSizeMb = System.getenv("S3_CACHE_MAX_MB")?.toLong() // null = no eviction
        requestChecksumCalculation = System.getenv("S3_CACHE_CHECKSUMS") ?: "WHEN_SUPPORTED"
        isPull = true
        isPush = isCi
        // Enable only when a bucket is configured.
        isEnabled = System.getenv("S3_CACHE_BUCKET") != null
    }
}

rootProject.name = "example"
