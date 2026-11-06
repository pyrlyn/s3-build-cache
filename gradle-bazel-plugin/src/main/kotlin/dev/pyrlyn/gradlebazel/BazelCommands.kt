package dev.pyrlyn.gradlebazel

import java.net.URI

/** Pure, side-effect-free helpers so command building and validation can be unit tested. */
object BazelCommands {
    private val LABEL = Regex("""^@{0,2}[A-Za-z0-9_.\-+]*//[A-Za-z0-9_.\-+/]*(:[A-Za-z0-9_.\-+/=,@~]+)?$""")
    private val SAFE_FILE_NAME = Regex("""^[A-Za-z0-9_.\-+]+$""")

    fun validate(targets: List<String>, outputNames: Map<String, String>, remoteCacheUrl: String?) {
        require(targets.isNotEmpty()) { "bazelBuild: at least one target label is required" }
        for (t in targets) {
            require(LABEL.matches(t)) { "bazelBuild: '$t' is not an absolute Bazel label (expected //pkg:name)" }
            require(!t.endsWith("...") && !t.contains(":all") && !t.endsWith(":*")) {
                "bazelBuild: wildcard target '$t' is not supported; outputs must be declared per target"
            }
        }
        for ((label, name) in outputNames) {
            require(label in targets) { "bazelBuild: outputName key '$label' is not in targets" }
            require(SAFE_FILE_NAME.matches(name) && name != "." && name != "..") {
                "bazelBuild: output name '$name' must be a plain file name"
            }
        }
        if (remoteCacheUrl != null) validateRemoteCacheUrl(remoteCacheUrl)
    }

    fun validateRemoteCacheUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrElse { throw IllegalArgumentException("remote cache URL is not a valid URI") }
        require(uri.scheme in setOf("http", "https", "grpc", "grpcs")) {
            "remote cache URL must use http, https, grpc or grpcs; Bazel cannot talk to s3:// directly (use bazel-remote as an S3 proxy)"
        }
        require(uri.userInfo == null) {
            "remote cache URL must not embed credentials; pass auth via a git-ignored .bazelrc (--remote_header) instead"
        }
    }

    /** Flags that affect where results come from, never what is built. Kept out of the Gradle fingerprint. */
    fun remoteFlags(remoteCacheUrl: String?, upload: Boolean): List<String> =
        if (remoteCacheUrl == null) emptyList()
        else listOfNotNull("--remote_cache=$remoteCacheUrl", if (upload) null else "--noremote_upload_local_results")

    fun build(exe: String, startup: List<String>, args: List<String>, remote: List<String>, targets: List<String>) =
        listOf(exe) + startup + "build" + args + remote + "--" + targets

    fun cqueryFiles(exe: String, startup: List<String>, args: List<String>, target: String) =
        listOf(exe) + startup + "cquery" + args + "--output=files" + "--" + target

    fun executionRoot(exe: String, startup: List<String>, args: List<String>) =
        listOf(exe) + startup + "info" + args + "execution_root"
}
