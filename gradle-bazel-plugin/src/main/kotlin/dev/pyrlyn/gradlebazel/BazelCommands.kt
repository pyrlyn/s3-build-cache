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

    fun outputGroupFlags(groups: List<String>): List<String> {
        groups.forEach { require(Regex("^[A-Za-z0-9_+\\-]+$").matches(it)) { "invalid output group '$it'" } }
        return if (groups.isEmpty()) emptyList() else listOf("--output_groups=" + groups.joinToString(","))
    }

    /** Remote cache and output root are owned by the plugin so they stay consistent and out of / in the fingerprint correctly. */
    fun validateArgs(startup: List<String>, build: List<String>) {
        startup.forEach { require(!it.startsWith("--output_user_root")) { "use outputUserRoot instead of startupArgs '--output_user_root'" } }
        build.forEach {
            require(!it.startsWith("--remote_") && !it.startsWith("--noremote_") && !it.startsWith("--disk_cache") && !it.startsWith("--output_groups")) {
                "buildArgs must not contain '$it'; remote cache and output groups are configured by the plugin"
            }
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

    private fun targetSet(targets: List<String>) = "set(" + targets.joinToString(" ") { "\"$it\"" } + ")"

    fun sourceInputs(exe: String, startup: List<String>, args: List<String>, targets: List<String>) =
        listOf(exe) + startup + "cquery" + args + listOf("--output=label", "--",
            "kind(\"source file\", deps(${targetSet(targets)}))")

    // buildfiles/loadfiles are unavailable in cquery. Loading-phase query conservatively includes
    // metadata from all select branches; configuration-only build flags are not valid query flags.
    fun buildFiles(exe: String, startup: List<String>, targets: List<String>) =
        listOf(exe) + startup + listOf("query", "--output=label", "--", "buildfiles(deps(${targetSet(targets)}))")

    fun executionRoot(exe: String, startup: List<String>, args: List<String>) =
        listOf(exe) + startup + "info" + args + "execution_root"
}
