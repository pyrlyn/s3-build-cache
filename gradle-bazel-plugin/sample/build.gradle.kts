import dev.pyrlyn.gradlebazel.BazelBuild

plugins {
    id("dev.pyrlyn.gradle-bazel")
}

bazel {
    workspaceDirectory.set(layout.projectDirectory)
    executable.set(providers.gradleProperty("bazel.executable").orElse("bazelisk"))

    // Option A: an externally managed Bazel remote cache (bazel-remote, NativeLink, ...).
    remoteCacheUrl.set(providers.environmentVariable("BAZEL_REMOTE_CACHE_URL"))
    remoteUpload.set(providers.environmentVariable("CI").map { true }.orElse(false))

    // Option B: let the plugin run bazel-remote for the duration of the build, proxying to the
    // same S3-compatible bucket as dev.pyrlyn.s3-build-cache but under its own prefix.
    // Credentials are read by bazel-remote from S3_CACHE_ACCESS_KEY_ID / S3_CACHE_SECRET_ACCESS_KEY.
    bazelRemote {
        enabled.set(providers.environmentVariable("S3_CACHE_BUCKET").map { true }.orElse(false))
        executable.set(providers.gradleProperty("bazelRemote.executable").orElse("bazel-remote"))
        s3Endpoint.set(providers.environmentVariable("S3_CACHE_ENDPOINT").orElse("s3.amazonaws.com"))
        s3Bucket.set(providers.environmentVariable("S3_CACHE_BUCKET"))
        s3Region.set(providers.environmentVariable("S3_CACHE_REGION"))
        s3Prefix.set("bazel-remote")
        disableSsl.set(providers.environmentVariable("S3_CACHE_DISABLE_SSL").map { it.toBoolean() }.orElse(false))
    }
}

// Machine-local Bazel output root (kept out of the Gradle fingerprint).
val outputUserRoot = providers.gradleProperty("bazel.outputUserRoot")

// Files every target depends on. Keep this explicit: a whole-workspace glob also hashes bazel-* symlinks.
val bazelConfig = files("MODULE.bazel", ".bazelversion", ".bazelrc", "third_party")

fun registerBazel(name: String, label: String, dir: String, configure: BazelBuild.() -> Unit = {}) =
    tasks.register<BazelBuild>(name) {
        description = "Builds $label with Bazel"
        targets.add(label)
        inputFiles.from(bazelConfig, dir)
        startupArgs.addAll(outputUserRoot.map { listOf("--output_user_root=$it") }.orElse(emptyList()))
        configure()
    }

val bazelC = registerBazel("bazelC", "//c:hello", "c")
val bazelKotlinJvm = registerBazel("bazelKotlinJvm", "//kotlin/jvm:hello.jar", "kotlin/jvm") {
    outputNames.put("//kotlin/jvm:hello.jar", "hello-kotlin-jvm.jar")
}
val bazelKotlinJs = registerBazel("bazelKotlinJs", "//kotlin/js:hello", "kotlin/js")
val bazelKotlinWasm = registerBazel("bazelKotlinWasm", "//kotlin/wasm:hello", "kotlin/wasm")
val bazelKotlinNative = registerBazel("bazelKotlinNative", "//kotlin/native:hello", "kotlin/native") {
    // Inherit KONAN_DATA_DIR from the environment without putting a machine path into the fingerprint.
    buildArgs.add("--action_env=KONAN_DATA_DIR")
}

// The JVM target names the implicit output file (hello.jar) so the .jdeps side output is not exported.
// Exposing a Bazel output as a regular Gradle artifact / file dependency.
val bazelJars by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(bazelJars.name, bazelKotlinJvm.map { it.outputDirectory.file("hello-kotlin-jvm.jar") }) {
    builtBy(bazelKotlinJvm)
}
