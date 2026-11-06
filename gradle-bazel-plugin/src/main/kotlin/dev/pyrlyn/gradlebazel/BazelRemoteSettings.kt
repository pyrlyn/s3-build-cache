package dev.pyrlyn.gradlebazel

/**
 * Builds the bazel-remote (https://github.com/buchgr/bazel-remote, tested with v2.6.4) command line and
 * environment for an S3 proxy backend. Credentials are only ever placed in the child environment
 * (BAZEL_REMOTE_S3_*), never in arguments, logs or task inputs.
 */
data class BazelRemoteSettings(
    val executable: String,
    val cacheDir: String,
    val maxSizeGb: Int,
    val httpPort: Int,
    val s3Endpoint: String,
    val s3Bucket: String,
    val s3Prefix: String,
    val s3Region: String?,
    val disableSsl: Boolean,
) {
    init {
        require(maxSizeGb > 0) { "bazelRemote.maxSizeGb must be > 0" }
        require(httpPort in 1..65535) { "bazelRemote.port must be 1..65535" }
        require(s3Bucket.isNotBlank()) { "bazelRemote.s3Bucket is required" }
        require(s3Endpoint.isNotBlank() && !s3Endpoint.contains("://")) {
            "bazelRemote.s3Endpoint must be host[:port] without scheme (use disableSsl for http)"
        }
        require(!s3Prefix.startsWith("/") && !s3Prefix.contains("..")) { "bazelRemote.s3Prefix must be relative" }
    }

    val url: String get() = "http://127.0.0.1:$httpPort"

    fun command(): List<String> = listOfNotNull(
        executable,
        "--dir=$cacheDir",
        "--max_size=$maxSizeGb",
        "--http_address=127.0.0.1:$httpPort",
        "--grpc_address=none",
        "--access_log_level=none",
        "--s3.endpoint=$s3Endpoint",
        "--s3.bucket=$s3Bucket",
        "--s3.prefix=$s3Prefix",
        "--s3.auth_method=access_key",
        s3Region?.let { "--s3.region=$it" },
        if (disableSsl) "--s3.disable_ssl" else null,
    )

    companion object {
        /** Same variable names as the dev.pyrlyn.s3-build-cache example, mapped onto bazel-remote's env names. */
        fun credentialEnv(source: Map<String, String>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            source["S3_CACHE_ACCESS_KEY_ID"]?.let { out["BAZEL_REMOTE_S3_ACCESS_KEY_ID"] = it }
            source["S3_CACHE_SECRET_ACCESS_KEY"]?.let { out["BAZEL_REMOTE_S3_SECRET_ACCESS_KEY"] = it }
            source["S3_CACHE_SESSION_TOKEN"]?.let { out["BAZEL_REMOTE_S3_SESSION_TOKEN"] = it }
            return out
        }
    }
}
