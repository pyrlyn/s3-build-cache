# s3-build-cache

Gradle settings plugin: remote build cache on any S3-compatible store (AWS S3, Cloudflare R2, MinIO). Client is AWS SDK v2.
Entries are stored as objects `<prefix><cache-key hash>`. If the store is unreachable, Gradle disables the remote cache for the rest of the build; the build does not fail.

## Setup
1. In `gradle.properties`: `org.gradle.caching=true`.
2. In `settings.gradle.kts` apply plugin `dev.pyrlyn.s3-build-cache` and configure `remote<S3BuildCache>` (see `example/settings.gradle.kts`).
3. Reads use `isPull` (default `true`); writes use `isPush`. Typically only CI pushes: `isPush = System.getenv("CI") != null`.
4. Pass credentials via environment variables; do not commit them.

## Parameters
| Parameter | Default | Description |
|---|---|---|
| `endpoint` | `null` (AWS) | S3-compatible server URL |
| `bucket` | — | required |
| `region` | `us-east-1` | `auto` for R2 |
| `accessKeyId` / `secretAccessKey` | `null` | without them — default AWS credential chain |
| `pathStyle` | `false` | `true` for MinIO and R2 |
| `prefix` | `gradle-cache/` | shared namespace for entries |
| `isPull` / `isPush` | `true` / `false` | read / write |
| `maxConnections` | `50` | HTTP connection pool (Apache client) |
| `connectionTimeoutMs` / `socketTimeoutMs` | `10000` / `60000` | connection timeouts |
| `connectionTimeToLiveMs` | `60000` | connection TTL in the pool |
| `apiCallTimeoutMs` / `apiCallAttemptTimeoutMs` | `300000` / `120000` | overall call / single-attempt timeout; retries use STANDARD mode |
| `requestChecksumCalculation` | `WHEN_SUPPORTED` | `WHEN_REQUIRED` for older S3-compatible servers |
| `multipartThresholdBytes` | 16 MiB | multipart upload above this size |
| `multipartPartSizeBytes` | 8 MiB | part size, minimum 5 MiB |
| `maxSizeBytes` / `maxSizeMb` | `null` | prefix size limit; `null` disables eviction |
| `lruRefreshAfterMs` | 1 h | see “Eviction” |

## How it works
- Object key is only `<prefix><Gradle cache-key hash>`, with no project name or paths (pure content-addressed layout).
- Load is a single `GET` with no `HEAD`; 404 is a miss. Store is a `PUT` with no prior `HEAD`.
- Entries from 16 MiB use multipart: first to a temp file (under the Gradle daemon’s `java.io.tmpdir`), then part uploads; on failure the upload is aborted (`AbortMultipartUpload`).
- Responses are verified by checksum when the server returns one (`WHEN_SUPPORTED`). Requests compute CRC32 by default; if an older server rejects it, set `requestChecksumCalculation = "WHEN_REQUIRED"`. Both modes were verified on MinIO.

## Shared cache across projects
Use the same `bucket` and `prefix` in both projects:

```kotlin
// project-a/settings.gradle.kts and project-b/settings.gradle.kts
buildCache {
    remote<S3BuildCache> {
        endpoint = "https://<ACCOUNT_ID>.r2.cloudflarestorage.com"
        bucket = "gradle-cache"
        region = "auto"
        pathStyle = true
        prefix = "shared/"   // same prefix
        isPush = System.getenv("CI") != null
    }
}
```

Matching tasks with the same inputs get the same key, so the cache is shared. Different Gradle, plugin, or JDK versions change task inputs and hashes diverge on their own — no collisions, no separate prefix needed for that. Use a separate prefix only when you want separate quotas or cleanup.

## AWS S3
- Leave `endpoint` unset, set `region` to the bucket region (for example `eu-central-1`), `pathStyle = false`.
- Without keys, the default AWS chain is used (env `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`, profile, role).
- IAM permissions: `s3:GetObject`, `s3:PutObject` on `arn:aws:s3:::<bucket>/<prefix>*`.

## Cloudflare R2
- `endpoint = "https://<ACCOUNT_ID>.r2.cloudflarestorage.com"`, `region = "auto"`, `pathStyle = true`.
- Keys: R2 → Manage API Tokens → token with Object Read & Write on the bucket.

## MinIO
- `endpoint = "http://host:9000"`, `region = "us-east-1"`, `pathStyle = true`.

## Eviction (optional)
`maxSizeMb = 5000` (or `maxSizeBytes`) turns on eviction: after each successful store the plugin lists objects under the prefix (`ListObjectsV2` with pagination), sums sizes, and deletes the oldest by `LastModified` (`DeleteObjects` in batches of up to 1000) until under the limit.

S3 has no access time, so LRU is approximate: on a hit, if the entry is older than `lruRefreshAfterMs` (default 1 h), the plugin refreshes `LastModified` by copying the object onto itself (`CopyObject`, `REPLACE`). The threshold limits extra requests.

Limits:
- Listing on every store costs requests; for large prefixes (hundreds of thousands of objects) prefer a lifecycle rule.
- Parallel builds can race: briefly exceed the limit or delete an entry someone just read. For a cache that is fine — it is just a miss.
- Eviction and refresh errors do not fail the build; a warning is logged.

Needs `s3:ListBucket` and `s3:DeleteObject` (on R2 — Object Read & Write).

## Cleanup without the plugin
Without `maxSizeBytes` the plugin deletes nothing. Configure a bucket lifecycle rule (supported on S3 and R2), for example delete objects under the prefix older than 30 days.

## Build
```
./gradlew build                 # compile and unit tests (MockK + in-process fake S3)
./gradlew e2eTest               # e2e: real MinIO (Testcontainers), plus real Bazel/bazel-remote for gradle-bazel; skipped without Docker or tools (`mise install`)
./gradlew publishToMavenLocal   # local Maven publish
./gradlew publish               # publish to GitHub Packages (CI; needs credentials)
cd example && ../gradlew help   # verify the plugin resolves
```

## Publishing

Both plugins are published: `dev.pyrlyn:s3-build-cache` and `dev.pyrlyn:gradle-bazel-plugin`, each with its plugin marker.

| Target | When |
|---|---|
| Maven Local (`~/.m2`) | `./gradlew publishToMavenLocal` for local use |
| GitHub Packages | CI on `main` and version tags (`v*`); Maven repo `https://maven.pkg.github.com/pyrlyn/s3-build-cache` |
| Gradle Plugin Portal / Maven Central | not configured yet (would be the usual public destinations for a Gradle plugin once Portal/OSSRH credentials exist) |

E2E defaults to image `cgr.dev/chainguard/minio:latest` (official `minio/minio` images no longer pull from Docker Hub). Override with `-De2e.minioImage=...` or `MINIO_IMAGE`. For Colima the Docker socket is picked up automatically.

## Gradle -> Bazel bridge

See [`gradle-bazel-plugin/`](gradle-bazel-plugin/README.md) for a separate plugin (`dev.pyrlyn.gradle-bazel`) that runs Bazel targets from Gradle with an optional S3-backed Bazel remote cache. It does not change this plugin.
