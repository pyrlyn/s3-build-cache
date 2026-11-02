package dev.pyrlyn.s3buildcache

import org.gradle.caching.BuildCacheService
import org.gradle.caching.BuildCacheServiceFactory
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI
import java.time.Duration

class S3BuildCacheServiceFactory : BuildCacheServiceFactory<S3BuildCache> {
    override fun createBuildCacheService(
        config: S3BuildCache,
        describer: BuildCacheServiceFactory.Describer,
    ): BuildCacheService {
        val bucket = requireNotNull(config.bucket) { "s3 build cache: 'bucket' is not set" }
        val checksum = parseChecksumCalculation(config.requestChecksumCalculation)

        describer.type("S3")
            .config("endpoint", config.endpoint ?: "aws-default")
            .config("bucket", bucket)
            .config("region", config.region)
            .config("prefix", config.prefix)
            .config("pathStyle", config.pathStyle.toString())
            .config("pull", config.isPull.toString())
            .config("requestChecksumCalculation", checksum.name)
            .config("maxSizeBytes", config.maxSizeBytes?.toString() ?: "unlimited")

        val options = optionsFrom(config, checksum)
        return S3BuildCacheService(buildClient(config, checksum), bucket, config.prefix, options)
    }

    companion object {
        fun parseChecksumCalculation(value: String): RequestChecksumCalculation =
            when (value.trim().uppercase()) {
                "WHEN_SUPPORTED" -> RequestChecksumCalculation.WHEN_SUPPORTED
                "WHEN_REQUIRED" -> RequestChecksumCalculation.WHEN_REQUIRED
                else -> throw IllegalArgumentException(
                    "s3 build cache: requestChecksumCalculation must be WHEN_SUPPORTED or WHEN_REQUIRED, got '$value'",
                )
            }

        fun optionsFrom(config: S3BuildCache, checksum: RequestChecksumCalculation) = S3CacheOptions(
            pull = config.isPull,
            push = config.isPush,
            multipartThresholdBytes = config.multipartThresholdBytes,
            multipartPartSizeBytes = config.multipartPartSizeBytes,
            multipartChecksums = checksum == RequestChecksumCalculation.WHEN_SUPPORTED,
            maxSizeBytes = config.maxSizeBytes,
            lruRefreshAfterMs = config.lruRefreshAfterMs,
        )

        fun buildClient(config: S3BuildCache, checksum: RequestChecksumCalculation): S3Client {
            val keyId = config.accessKeyId
            val secret = config.secretAccessKey
            val credentials = if (keyId != null && secret != null) {
                StaticCredentialsProvider.create(AwsBasicCredentials.create(keyId, secret))
            } else {
                DefaultCredentialsProvider.builder().build()
            }
            val http = ApacheHttpClient.builder()
                .maxConnections(config.maxConnections)
                .connectionTimeout(Duration.ofMillis(config.connectionTimeoutMs))
                .socketTimeout(Duration.ofMillis(config.socketTimeoutMs))
                .connectionTimeToLive(Duration.ofMillis(config.connectionTimeToLiveMs))
            val override = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(config.apiCallTimeoutMs))
                .apiCallAttemptTimeout(Duration.ofMillis(config.apiCallAttemptTimeoutMs))
                .retryStrategy(RetryMode.STANDARD)
                .build()
            return S3Client.builder()
                .region(Region.of(config.region))
                .credentialsProvider(credentials)
                .httpClientBuilder(http)
                .overrideConfiguration(override)
                .forcePathStyle(config.pathStyle)
                .requestChecksumCalculation(checksum)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_SUPPORTED)
                .apply { config.endpoint?.let { endpointOverride(URI.create(it)) } }
                .build()
        }
    }
}
