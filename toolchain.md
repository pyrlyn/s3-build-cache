# Toolchain

## Programs

| Program | How to install | Why here | Source |
| --- | --- | --- | --- |
| JDK 21+ (Temurin) | mise (machine) / `actions/setup-java` in CI | Runs Gradle and the plugins | https://github.com/adoptium/temurin-build |
| Gradle 9.8.2 | wrapper (`./gradlew`) | Build | https://github.com/gradle/gradle |
| bazelisk 1.30.0 | mise (`mise.toml`) | Runs the Bazel version pinned in `.bazelversion` (gradle-bazel e2e, sample) | https://github.com/bazelbuild/bazelisk |
| Bazel 9.3.2 | downloaded by bazelisk | Builds the e2e workspace and the sample | https://github.com/bazelbuild/bazel |
| bazel-remote 2.6.4 | mise (`mise.toml`, `github:` backend) | S3 proxy for the Bazel remote cache (gradle-bazel e2e) | https://github.com/buchgr/bazel-remote |
| Docker (Colima on macOS) | mise / system | Testcontainers MinIO for both e2e suites | https://github.com/abiosoft/colima |
| MinIO image `cgr.dev/chainguard/minio` | pulled by Testcontainers | S3-compatible store for e2e | https://github.com/chainguard-images/images |

## Gradle (`gradle/libs.versions.toml`)

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| org.jetbrains.kotlin.jvm | local | https://github.com/JetBrains/kotlin | Kotlin compiler plugin |
| software.amazon.awssdk:s3, apache-client | local | https://github.com/aws/aws-sdk-java-v2 | S3 client of the build cache; S3 assertions in e2e |
| org.junit:junit-bom | local | https://github.com/junit-team/junit-framework | Tests |
| io.mockk:mockk | local | https://github.com/mockk/mockk | Unit test mocks |
| org.testcontainers:testcontainers-minio, -junit-jupiter | local | https://github.com/testcontainers/testcontainers-java | MinIO in e2e tests |
| gradleTestKit | local (Gradle) | https://github.com/gradle/gradle | Functional and e2e builds of gradle-bazel |

## mise (`mise.toml`)

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| bazelisk | global | https://github.com/bazelbuild/bazelisk | gradle-bazel e2e |
| github:buchgr/bazel-remote | global | https://github.com/buchgr/bazel-remote | gradle-bazel e2e |

## GitHub Actions

| Package | Where | Source | Why here |
| --- | --- | --- | --- |
| actions/checkout, actions/setup-java, actions/upload-artifact | CI | https://github.com/actions | Build, test, artifacts |
| jdx/mise-action | CI | https://github.com/jdx/mise-action | Installs the `mise.toml` tools for e2e |
