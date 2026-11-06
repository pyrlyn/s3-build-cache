# dev.pyrlyn.gradle-bazel

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
    outputGroups.add("default")                                    // optional --output_groups, fingerprinted
    outputUserRoot.set("/path/on/this/machine")                    // normalized out of the fingerprint
    inputFiles.from("bazel/third_party")                          // additional repository-rule inputs
    assumeHermetic.set(true)                                       // opt in to caching, see below
    includeRunfiles.set(true)                                      // also copy <exe>.runfiles (executables)
}

// Consume it like any other file/artifact:
dependencies { implementation(files(bazelLib.map { it.outputDirectory.file("hello.jar") })) }
```

Outputs land in `build/bazel/<taskName>/` (override with `outputDirectory`).

Published to GitHub Packages as `dev.pyrlyn:gradle-bazel-plugin` with the plugin marker
`dev.pyrlyn.gradle-bazel` (Maven repo `https://maven.pkg.github.com/pyrlyn/s3-build-cache`).

## How it works

0. Before Gradle up-to-date/cache checks, discover workspace inputs (see below).
1. `bazel build <buildArgs> [remote flags] -- <targets>`
2. `bazel info <buildArgs> execution_root output_base install_base`
3. For each target, `bazel cquery <buildArgs> --output=files -- <target>` lists the target's default
   outputs (paths relative to the execution root). Files are copied (not symlinked) into the task
   output directory; a tree artifact (directory output) is copied recursively into a directory of
   the same name. With `includeRunfiles`, the `<file>.runfiles` tree next to each executable output
   is copied to `<name>.runfiles/` so the executable runs from the Gradle output.

Copies dereference symlinks: Gradle outputs and build cache entries never point back into a
machine-local Bazel output base. Every copied path, and every symlink target inside a tree or
runfiles, must resolve under the workspace, the output base or the install base; anything else
(a symlink to a host file, a dangling link, a cycle) fails the task. Paths from cquery that are
absolute or contain `..` are rejected. The runfiles `MANIFEST` is not copied because it lists
absolute machine paths; runfiles libraries fall back to the directory layout. Runfiles of
external repositories that live outside the output base (e.g. `local_path_override` pointing
outside the workspace) are refused by the same rule.

`cquery --output=files` was chosen over the Build Event Protocol because it is a single, stable
command per target, honours the same configuration flags, and needs no JSON/proto parsing.

## Inputs, up-to-date checks and caching

`BazelBuild` fingerprints: `executable`, `targets`, `outputNames`, `buildArgs`, `outputGroups`,
`startupArgs`, the workspace path relative to the root project, `assumeHermetic`, the remote
cache identity (external URL, or bazel-remote endpoint/bucket/region/prefix/port/ssl/upload) and
`remoteUpload`, `discoverSourceInputs`, the sorted discovered workspace-relative paths, plus
file contents of discovered inputs, additional `inputFiles` and the workspace config files
(`.bazelversion`, `MODULE.bazel`, `MODULE.bazel.lock`, `.bazelrc`, `WORKSPACE[.bazel]`, when present).
Directories in `inputFiles` are hashed recursively by file content and path relative to the
directory, never by absolute path. Only `outputUserRoot` (machine-local location) and credentials
are left out. `--remote_*`, `--noremote_*`, `--disk_cache` and `--output_groups` are rejected in
`buildArgs` so they cannot bypass these rules, and `--output_user_root` in `startupArgs`.

### Automatic local source inputs (default ON)

Before every input snapshot (even an UP-TO-DATE or FROM-CACHE build), the task runs:

- `bazel cquery <buildArgs> <output-group flags> --output=label -- 'kind("source file", deps(set("//pkg:target")))'`
- `bazel query --output=label -- 'buildfiles(deps(set("//pkg:target")))'`

Both use the same executable, startup options and output user root as the build. The configured
query follows selected dependencies, including sources of rules that generate intermediate files;
generated outputs themselves are not Gradle inputs. Bazel traverses the graph, not filesystem
symlinks. The loading-phase query includes transitive BUILD and loaded `.bzl` files (including
packages containing only extensions). It conservatively includes all `select()` branches because
`buildfiles` is unavailable in cquery; build/configuration flags are not passed to `query` because
it does not accept them. Repository graph selection must be shared by build and query (use
`common` rc entries, not build-only repository overrides). Incompatible loading/query options fail closed rather than silently
under-declaring inputs. Cycles or other Bazel errors fail the task before cache lookup.

Only main-workspace labels (`//`, `@//`, `@@//`) become file inputs, with relative paths and
contents fingerprinted. External repository labels are excluded: downloaded tools, Bazel output
roots, generated files and machine-local caches are never automatically hashed. Missing files,
malformed local labels, wildcard path characters (`*`, `?`), traversal and symlinks escaping the
workspace fail discovery. An exact-include file tree rooted at the workspace preserves the
path/content association even when different packages contain identically named files. Query
results are memoized only for the current task instance, never persisted between Gradle runs.
This adds two Bazel queries per task, including on an otherwise UP-TO-DATE build.

`inputFiles` remains **additive**: declare repository-rule inputs (e.g. local BUILD templates used
by MODULE extensions), imported rc files, local repositories, environment-derived data and other
inputs outside this graph explicitly. Auto-discovery is not complete toolchain/environment
tracking. Home/system rc files, `user.bazelrc`, nested MODULE includes/extensions, untracked glob
membership effects on repository rules, downloads at action time and host JDK/Xcode/sysroots are
not automatically fingerprinted. Never add credential-bearing rc files as cache inputs; keep
auth separate and use a non-secret input for any output-affecting configuration.

For incompatible Bazel versions/workspaces, set `discoverSourceInputs.set(false)` on the task
and declare **all** sources and package definitions in `inputFiles`. There is no silent fallback.
Avoid whole-workspace globs: they can traverse `bazel-*` convenience symlinks and local caches.
Concurrent edits during a build are unsupported (as with ordinary Gradle input snapshots).

**`assumeHermetic` (default `false`)**: with the default, the task is never stored in or restored
from the Gradle build cache and Bazel runs without any remote cache (a lifecycle line says so);
local UP-TO-DATE checks still apply. Set it to `true` only when declared inputs fully determine the
outputs. Toolchains that download things at action time (Kotlin/Native's `KONAN_DATA_DIR`) are not
hermetic; a future hook could pin known dependency/sysroot hashes as inputs.

## Configuration cache

`BazelBuild` and the managed bazel-remote service are compatible with Gradle's configuration
cache. Source discovery is never part of a configuration cache entry: Bazel is queried when task
inputs are snapshotted, also when an entry is reused, so a source edit still reruns the task.
Credentials are read from the environment when bazel-remote starts, so they never enter an entry.

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

Authentication (`bazelRemote.authMethod`, the values of bazel-remote's `--s3.auth_method`):

| `authMethod` | Credentials | Extra settings |
|---|---|---|
| `access_key` (default) | Environment only, same names as the s3-build-cache example: `S3_CACHE_ACCESS_KEY_ID`, `S3_CACHE_SECRET_ACCESS_KEY`, optional `S3_CACHE_SESSION_TOKEN`. Missing keys fail the task before bazel-remote starts. | — |
| `iam_role` | Standard AWS locations (EC2/ECS metadata, web identity) as resolved by bazel-remote | `iamRoleEndpoint` (optional) |
| `aws_credentials_file` | An AWS shared credentials file outside the repository | `credentialsFile`, `awsProfile` (both optional; bazel-remote defaults to `~/.aws/credentials` / `default`) |

Keys are passed to the child process as `BAZEL_REMOTE_S3_*` environment variables, never as
arguments, log lines, or task inputs; a credentials file is passed by path only. The auth method
does not change the cache identity, so switching it does not invalidate tasks.

Same bucket, **not** the same keys: Gradle's cache entries and Bazel's AC/CAS entries use
unrelated key schemes. Sharing a bucket (with distinct prefixes) saves infrastructure, it does not
make a Gradle-built JAR reusable by Bazel or vice versa. Eviction by `dev.pyrlyn.s3-build-cache`
applies only to its own prefix.

**Read-only by default, in both modes.** Unless `bazel.remoteUpload` is true, Bazel gets
`--noremote_upload_local_results` and the managed bazel-remote is started with
`--num_uploaders=0`, so nothing is written to S3 (bazel-remote still writes its local disk tier
under `build/bazel-remote/cache`, which is local only). Typical: `remoteUpload = CI`.

Object layout observed with bazel-remote v2.6.4 against MinIO: `<bucket>/<s3Prefix>/ac/...` and
`<bucket>/<s3Prefix>/cas.v2/...` (e.g. `bazel-remote/ac/...`). There is no separate index object.

## Sample (`sample/`)

One Bazel workspace, five targets, each built by its own Gradle task:

| Gradle task | Bazel target | How it is built | Caching |
|---|---|---|---|
| `bazelC` | `//c:hello` | `cc_binary` from official `rules_cc` | `assumeHermetic=true` (host C toolchain) |
| `bazelKotlinJvm` | `//kotlin/jvm:hello.jar` | `kt_jvm_library` from official `rules_kotlin` | `assumeHermetic=true` |
| `bazelKotlinJs` | `//kotlin/js:hello` | custom `genrule` around official `kotlinc-js` 2.4.21 (pinned `http_archive`) | `assumeHermetic=true`; JDK from PATH |
| `bazelKotlinWasm` | `//kotlin/wasm:hello` | custom `genrule` around official `kotlinc-wasm` (wasm-js target) | `assumeHermetic=true`; JDK from PATH |
| `bazelKotlinNative` | `//kotlin/native:hello` | custom `genrule` around official `kotlinc-native` prebuilt, macOS arm64 only | `assumeHermetic=false` + Bazel tag `no-remote-cache`: not cached anywhere except local UP-TO-DATE |

`rules_kotlin` covers only the JVM; the JS/Wasm/Native targets are custom wrappers written for
this sample (other community rule sets were not evaluated). The `cc_binary` default output on
macOS fastbuild is the executable itself (verified with `cquery --output=files`); runfiles are not
copied, which is fine for this target. `verifyArtifacts` checks the exported files: Mach-O magic for
C/Native, zip magic for the JAR, `\0asm` for the Wasm module, compiled JS containing the program.

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
- Default outputs unless `outputGroups` is set. Name an implicit output file (e.g. `:hello.jar`)
  to pick one file of a multi-output rule.
- One configuration per task: multi-platform builds need one task per `--platforms` value.
- Runfiles are copied as a directory tree only (no manifest), and only with `includeRunfiles`;
  with `--nobuild_runfile_links` there is no tree to copy and the task fails.
- Hermeticity of `genrule` wrappers that use the host JDK is assumed, not enforced.
- Bazel runs serially per Gradle build (the managed bazel-remote service has one usage slot, and
  Bazel's server locks the output base anyway).
- Automatic queries cover local graph files, not full toolchains.
- The plugin does not download Bazel or bazel-remote.

## Tests

`./gradlew :gradle-bazel-plugin:test` needs neither Bazel nor S3: unit tests cover command
building, output groups, reserved flags, read-only bazel-remote, credential isolation, fingerprint
identity and config validation; TestKit tests use a fake `bazel` script to check argument
forwarding, the exported artifact, UP-TO-DATE, which settings do/do not invalidate the task,
Gradle build cache restore for hermetic tasks and no caching for non-hermetic ones. Discovery
tests cover source/BUILD/loaded-extension edits, unrelated files, additive explicit inputs,
opt-out and fail-closed query errors/unsafe paths; further tests cover the configuration cache,
tree artifacts, runfiles, output containment and bazel-remote auth settings.

`./gradlew :gradle-bazel-plugin:e2eTest` drives real tools: bazelisk with the Bazel version pinned
in `src/e2eTest/workspace/.bazelversion`, bazel-remote and MinIO (Testcontainers). Install the
tools with `mise install` (versions in the root `mise.toml`); tests skip when a tool or Docker is
missing. Scenarios: genrule build, UP-TO-DATE, source/BUILD/`.bzl` edits and unrelated edits,
tree artifact, runfiles executable run from the Gradle output, build cache restore at another
project path, configuration cache reuse with rediscovery, fail-closed broken BUILD; managed
bazel-remote uploading to MinIO, remote cache hits from a fresh workspace authenticated with an
AWS credentials file, read-only mode writing nothing, non-hermetic tasks bypassing the remote
cache, and missing access keys failing without leaking secrets.
