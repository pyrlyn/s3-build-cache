# dev.pyrlyn.gradle-bazel (experimental MVP)

A small Gradle plugin that runs explicit Bazel targets from Gradle and exposes their output
files as ordinary Gradle task outputs / artifacts, with an optional Bazel remote cache backed by
the same S3-compatible bucket that `dev.pyrlyn.s3-build-cache` uses.

It is **not** a Bazel migration tool and does not model Kotlin Multiplatform. It is a bridge for
"build this one thing with Bazel, consume the file in Gradle".

## Usage

```kotlin
import dev.pyrlyn.gradlebazel.BazelBuild

plugins { id("dev.pyrlyn.gradle-bazel") }

bazel {
    workspaceDirectory.set(layout.projectDirectory.dir("bazel"))  // default: project dir
    executable.set("bazelisk")                                     // default: bazelisk (honours .bazelversion)
}

val bazelLib = tasks.register<BazelBuild>("bazelLib") {
    targets.add("//kotlin/jvm:hello.jar")                         // explicit labels only, no wildcards
    outputNames.put("//kotlin/jvm:hello.jar", "hello.jar")        // optional rename (single-file targets)
    buildArgs.addAll("-c", "opt")                                  // fingerprinted, also passed to cquery
    startupArgs.add("--output_user_root=/path/on/this/machine")   // not fingerprinted
    inputFiles.from("bazel/MODULE.bazel", "bazel/.bazelversion", "bazel/kotlin/jvm") // see "Inputs"
}

// Consume it like any other file/artifact:
dependencies { implementation(files(bazelLib.map { it.outputDirectory.file("hello.jar") })) }
```

Outputs land in `build/bazel/<taskName>/` (override with `outputDirectory`).

## How it works

1. `bazel build <buildArgs> [remote flags] -- <targets>`
2. `bazel info <buildArgs> execution_root`
3. For each target, `bazel cquery <buildArgs> --output=files -- <target>` lists the target's default
   output files (paths relative to the execution root). Only those files are copied (not symlinked)
   into the task output directory; paths that are absolute or contain `..` are rejected.

`cquery --output=files` was chosen over the Build Event Protocol because it is a single, stable
command per target, honours the same configuration flags, and needs no JSON/proto parsing.

## Inputs, up-to-date checks and the Gradle build cache

`BazelBuild` is `@CacheableTask`. Its fingerprint is: executable, targets, `outputNames`,
`buildArgs`, and the content (relative paths) of `inputFiles`. Gradle only knows what you put in
`inputFiles`, so declare every source, `BUILD`, `MODULE.bazel`, `.bazelversion` and `.bazelrc`
the targets depend on. Pointing it at the whole workspace is correct but heavy (it hashes every
file, including large trees) and must not include the `bazel-*` convenience symlinks. If you
under-declare inputs the task can be wrongly UP-TO-DATE; Bazel itself still rebuilds correctly
when the task does run.

Not fingerprinted (machine-local or "where results come from" only): `workspaceDirectory`,
`startupArgs`, and all remote cache settings.

## Remote cache on S3

Bazel cannot talk to S3 directly (`--remote_cache=s3://...` does not exist). Two options:

**A. Externally managed endpoint** (bazel-remote, NativeLink, BuildBuddy, ...):

```kotlin
bazel {
    remoteCacheUrl.set(providers.environmentVariable("BAZEL_REMOTE_CACHE_URL")) // http(s)/grpc(s) only
    remoteUpload.set(providers.environmentVariable("CI").map { true }.orElse(false))
}
```

URLs with embedded credentials are rejected; put auth headers in a git-ignored `user.bazelrc`.

**B. Plugin-managed [bazel-remote](https://github.com/buchgr/bazel-remote)** (tested with v2.6.4),
started lazily by the first `BazelBuild` task and stopped (SIGTERM, then forced after 15 s) when
the Gradle build finishes. It listens on `127.0.0.1:<port>` only, gRPC disabled, and proxies to S3:

```kotlin
bazel {
    bazelRemote {
        enabled.set(providers.environmentVariable("S3_CACHE_BUCKET").map { true }.orElse(false))
        executable.set("bazel-remote")       // must be installed; the plugin does not download it
        s3Endpoint.set("s3.amazonaws.com")   // host[:port], no scheme
        s3Bucket.set(providers.environmentVariable("S3_CACHE_BUCKET"))
        s3Region.set(providers.environmentVariable("S3_CACHE_REGION"))
        s3Prefix.set("bazel-remote")         // default; keep separate from the Gradle cache prefix
        disableSsl.set(false)                // true for a local http MinIO
        port.set(9090); maxSizeGb.set(5)     // local disk tier in build/bazel-remote/cache
    }
}
```

Credentials come only from the environment, using the same names as the s3-build-cache example
(`S3_CACHE_ACCESS_KEY_ID`, `S3_CACHE_SECRET_ACCESS_KEY`, optional `S3_CACHE_SESSION_TOKEN`). They
are passed to the child process as `BAZEL_REMOTE_S3_*` environment variables, never as arguments,
log lines, or task inputs. Only `--s3.auth_method=access_key` is wired up in this MVP.

Same bucket, **not** the same keys: Gradle's cache entries and Bazel's AC/CAS entries use
unrelated key schemes. Sharing a bucket (with distinct prefixes) saves infrastructure, it does not
make a Gradle-built JAR reusable by Bazel or vice versa. Eviction by `dev.pyrlyn.s3-build-cache`
applies only to its own prefix.

Read-only by default: unless `remoteUpload` is true, `--noremote_upload_local_results` is passed.

## Sample (`sample/`)

One Bazel workspace, five targets, each built by its own Gradle task:

| Gradle task | Bazel target | How it is built | Status |
|---|---|---|---|
| `bazelC` | `//c:hello` | `cc_binary` from official `rules_cc` | Real rule set, hermetic-ish (host C toolchain) |
| `bazelKotlinJvm` | `//kotlin/jvm:hello.jar` | `kt_jvm_library` from official `rules_kotlin` | Real rule set |
| `bazelKotlinJs` | `//kotlin/js:hello` | `genrule` around official `kotlinc-js` (Kotlin 2.4.21 distribution via `http_archive`) | **Wrapper**: no Bazel rules for Kotlin/JS exist (`rules_kotlin` dropped JS). Needs a JDK on PATH |
| `bazelKotlinWasm` | `//kotlin/wasm:hello` | `genrule` around official `kotlinc-wasm` (wasm-js target) | **Wrapper**: no Bazel rules for Kotlin/Wasm. Needs a JDK on PATH; runs on Node.js with Wasm GC |
| `bazelKotlinNative` | `//kotlin/native:hello` | `genrule` around official `kotlinc-native` prebuilt, macOS arm64 only | **Experimental, not hermetic**: no maintained Bazel rules for Kotlin/Native; first run downloads LLVM/sysroot into `KONAN_DATA_DIR` (unsandboxed, network), links with host Xcode/CLT. Its cache key does not cover those downloaded dependencies |

Each genrule compiles one single-file module (source → klib → JS/Wasm/executable). None of this
compiles a Kotlin Multiplatform project: there is no `commonMain`/`expect`/`actual`, no
dependency resolution, and no Gradle KMP model involved.

Run it (needs bazelisk; bazel-remote and an S3-compatible endpoint only for the cache):

```sh
cd gradle-bazel-plugin/sample
../../gradlew bazelC bazelKotlinJvm bazelKotlinJs bazelKotlinWasm bazelKotlinNative \
  -Pbazel.outputUserRoot=$HOME/.cache/bazel-sample        # optional
```

## Limitations

- Explicit labels only; no `//...`, `:all`, or `*`. One task can list several targets.
- Default outputs only; no output groups, no runfiles, no tree artifacts (directories fail fast).
  Name an implicit output file (e.g. `:hello.jar`) to pick one file of a multi-output rule.
- One configuration per task: multi-platform builds need one task per `--platforms` value.
- Bazel runs serially per Gradle build (the managed bazel-remote service has one usage slot, and
  Bazel's server locks the output base anyway).
- No Gradle configuration cache / Bazel query-driven input discovery yet.
- The plugin does not download Bazel or bazel-remote.

## Tests

`./gradlew :gradle-bazel-plugin:test` needs neither Bazel nor S3: unit tests cover command
building, remote flag handling, credential isolation and config validation; a TestKit test uses a
fake `bazel` script to check argument forwarding, the exported artifact, UP-TO-DATE on rerun, and
re-execution after an input change.
