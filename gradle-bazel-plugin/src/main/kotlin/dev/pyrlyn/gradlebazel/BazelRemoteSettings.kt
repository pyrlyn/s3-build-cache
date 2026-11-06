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
    /** false = read-only: bazel-remote is started with --num_uploaders=0 so nothing is written to S3. */
    val upload: Boolean = false,
    val authMethod: String = ACCESS_KEY,
    /** iam_role only; null = the standard AWS locations (env, web identity, ECS/EC2 metadata). */
    val iamRoleEndpoint: String? = null,
    /** aws_credentials_file only; null = bazel-remote's default (~/.aws/credentials or AWS_SHARED_CREDENTIALS_FILE). */
    val credentialsFile: String? = null,
    /** aws_credentials_file only; null = bazel-remote's default ("default" or AWS_PROFILE). */
    val awsProfile: String? = null,
) {
    init {
        require(maxSizeGb > 0) { "bazelRemote.maxSizeGb must be > 0" }
        require(httpPort in 1..65535) { "bazelRemote.port must be 1..65535" }
        require(s3Bucket.isNotBlank()) { "bazelRemote.s3Bucket is required" }
        require(s3Endpoint.isNotBlank() && !s3Endpoint.contains("://")) {
            "bazelRemote.s3Endpoint must be host[:port] without scheme (use disableSsl for http)"
        }
        require(!s3Prefix.startsWith("/") && !s3Prefix.contains("..")) { "bazelRemote.s3Prefix must be relative" }
        require(authMethod in AUTH_METHODS) { "bazelRemote.authMethod must be one of ${AUTH_METHODS.joinToString()}" }
        require(iamRoleEndpoint == null || authMethod == IAM_ROLE) { "bazelRemote.iamRoleEndpoint applies only to authMethod=$IAM_ROLE" }
        require((credentialsFile == null && awsProfile == null) || authMethod == CREDENTIALS_FILE) {
            "bazelRemote.credentialsFile and awsProfile apply only to authMethod=$CREDENTIALS_FILE"
        }
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
        "--s3.auth_method=$authMethod",
        iamRoleEndpoint?.let { "--s3.iam_role_endpoint=$it" },
        // A path, never the file's content: secrets stay in the file.
        credentialsFile?.let { "--s3.aws_shared_credentials_file=$it" },
        awsProfile?.let { "--s3.aws_profile=$it" },
        s3Region?.let { "--s3.region=$it" },
        if (disableSsl) "--s3.disable_ssl" else null,
        if (upload) null else "--num_uploaders=0",
    )

    /** Non-secret identity for task fingerprints. */
    val identity: String get() = identity(s3Endpoint, s3Bucket, s3Prefix, s3Region, disableSsl, httpPort, upload)

    companion object {
        const val ACCESS_KEY = "access_key"
        const val IAM_ROLE = "iam_role"
        const val CREDENTIALS_FILE = "aws_credentials_file"
        /** Values accepted by `--s3.auth_method` in bazel-remote v2.6.4. */
        val AUTH_METHODS = listOf(ACCESS_KEY, IAM_ROLE, CREDENTIALS_FILE)

        fun identity(endpoint: String?, bucket: String?, prefix: String?, region: String?, disableSsl: Boolean, port: Int, upload: Boolean) =
            "bazel-remote:s3://$endpoint/$bucket/$prefix region=${region ?: ""} ssl=${!disableSsl} port=$port upload=$upload"

        /**
         * Same variable names as the dev.pyrlyn.s3-build-cache example, mapped onto bazel-remote's env
         * names. Only access_key passes keys; error messages name the variables, never their values.
         */
        fun credentialEnv(authMethod: String, source: Map<String, String>): Map<String, String> {
            if (authMethod != ACCESS_KEY) return emptyMap()
            require(!source["S3_CACHE_ACCESS_KEY_ID"].isNullOrEmpty() && !source["S3_CACHE_SECRET_ACCESS_KEY"].isNullOrEmpty()) {
                "bazelRemote.authMethod=$ACCESS_KEY needs S3_CACHE_ACCESS_KEY_ID and S3_CACHE_SECRET_ACCESS_KEY in the environment"
            }
            val out = mutableMapOf<String, String>()
            source["S3_CACHE_ACCESS_KEY_ID"]?.let { out["BAZEL_REMOTE_S3_ACCESS_KEY_ID"] = it }
            source["S3_CACHE_SECRET_ACCESS_KEY"]?.let { out["BAZEL_REMOTE_S3_SECRET_ACCESS_KEY"] = it }
            source["S3_CACHE_SESSION_TOKEN"]?.let { out["BAZEL_REMOTE_S3_SESSION_TOKEN"] = it }
            return out
        }
    }
}
