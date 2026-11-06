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

// Files every target depends on. Keep this explicit: a whole-workspace glob also hashes bazel-* symlinks.
val bazelConfig = files("MODULE.bazel", ".bazelversion", ".bazelrc", "third_party")

fun registerBazel(name: String, label: String, dir: String, configure: BazelBuild.() -> Unit = {}) =
    tasks.register<BazelBuild>(name) {
        description = "Builds $label with Bazel"
        targets.add(label)
        inputFiles.from(bazelConfig, dir)
        outputUserRoot.set(providers.gradleProperty("bazel.outputUserRoot"))
        // Inputs below fully determine these outputs, so Gradle and Bazel caching are allowed.
        assumeHermetic.set(true)
        configure()
    }

val bazelC = registerBazel("bazelC", "//c:hello", "c")
val bazelKotlinJvm = registerBazel("bazelKotlinJvm", "//kotlin/jvm:hello.jar", "kotlin/jvm") {
    outputNames.put("//kotlin/jvm:hello.jar", "hello-kotlin-jvm.jar")
}
val bazelKotlinJs = registerBazel("bazelKotlinJs", "//kotlin/js:hello", "kotlin/js")
val bazelKotlinWasm = registerBazel("bazelKotlinWasm", "//kotlin/wasm:hello", "kotlin/wasm")
val bazelKotlinNative = registerBazel("bazelKotlinNative", "//kotlin/native:hello", "kotlin/native") {
    // NOT hermetic: kotlinc-native downloads LLVM/sysroots into KONAN_DATA_DIR and links with host Xcode,
    // none of which is fingerprinted. So: no Gradle build cache store/restore, no Bazel remote cache.
    // Future hook: pin known konan dependency/toolchain hashes as inputs, then flip this to true.
    assumeHermetic.set(false)
    // Inherit KONAN_DATA_DIR from the environment without putting a machine path into the fingerprint.
    buildArgs.add("--action_env=KONAN_DATA_DIR")
}

// The JVM target names the implicit output file (hello.jar) so the .jdeps side output is not exported.
// Exposing a Bazel output as a regular Gradle artifact / file dependency.
configurations.consumable("bazelJars")
artifacts.add("bazelJars", bazelKotlinJvm.map { it.outputDirectory.file("hello-kotlin-jvm.jar") }) {
    builtBy(bazelKotlinJvm)
}

// Checks that the exported files are real compiler outputs (magic bytes / content), not logs.
tasks.register("verifyArtifacts") {
    val dirs = listOf(bazelC, bazelKotlinJvm, bazelKotlinJs, bazelKotlinWasm, bazelKotlinNative)
        .map { t -> t.flatMap { it.outputDirectory } }
    dependsOn(dirs)
    val base = layout.buildDirectory.dir("bazel")
    doLast {
        val root = base.get().asFile
        fun magic(path: String, vararg bytes: Int) {
            val actual = File(root, path).readBytes().take(bytes.size).map { it.toInt() and 0xff }
            check(actual == bytes.toList()) { "$path: unexpected header $actual" }
        }
        magic("bazelC/hello", 0xcf, 0xfa, 0xed, 0xfe)                  // Mach-O 64-bit (macOS host)
        magic("bazelKotlinNative/hello.kexe", 0xcf, 0xfa, 0xed, 0xfe)
        magic("bazelKotlinJvm/hello-kotlin-jvm.jar", 0x50, 0x4b, 0x03, 0x04) // zip/JAR
        magic("bazelKotlinWasm/hello.wasm", 0x00, 0x61, 0x73, 0x6d)     // \0asm
        check(File(root, "bazelKotlinJs/hello.js").readText().contains("hello from Kotlin/JS"))
        check(File(root, "bazelKotlinWasm/hello.mjs").readText().contains("hello.wasm"))
        logger.lifecycle("All five Bazel outputs have the expected format")
    }
}
