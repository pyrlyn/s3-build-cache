# s3-build-cache

GitHub: https://github.com/pyrlyn/s3-build-cache

Gradle settings plugin for a remote build cache on S3-compatible storage, plus a separate Gradle -> Bazel bridge plugin (`gradle-bazel-plugin/`) with an optional S3-backed Bazel remote cache.

## Decisions

- bazel-remote S3 auth method names follow `bazel-remote --help` of v2.6.4: `access_key`, `iam_role`, `aws_credentials_file` (https://github.com/buchgr/bazel-remote/blob/v2.6.4/README.md, checked 2026-11-06).
