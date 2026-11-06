---
status: in progress
priority: P1
complexity: 4
readiness: 90%
agent: Claude Code / claude-opus-5-5
---

# T1. Finish the gradle-bazel plugin and add real e2e tests

`dev.pyrlyn.gradle-bazel` is an MVP: no configuration cache, no tree artifacts or runfiles,
only access-key auth for the managed bazel-remote, not published, and tested only against a fake
`bazel` script. Done means all four gaps are closed, the README no longer calls the plugin
experimental, and an `e2eTest` suite drives real Bazel, bazel-remote and MinIO locally and in CI.

## Execution plan

1. Project files: `AGENTS.md`, `plan/`, `roadmap.md`, `ideas.md`, `toolchain.md`, `mise.toml` (bazelisk, bazel-remote).
2. Configuration cache: `BazelBuild` without `project` at execution time (injected `ObjectFactory`),
   transient discovery memo, plugin conventions without `project` captured in providers.
   TestKit test with `--configuration-cache` (fake bazel): store, reuse, rerun on source edit.
3. Tree artifacts: a directory output is copied recursively into `<outputDirectory>/<name>/`.
   Runfiles: opt-in `includeRunfiles`; the sibling `<file>.runfiles` tree is copied with symlinks
   dereferenced. Every copied path (file, tree entry, runfiles link target) must resolve under the
   workspace or the Bazel output user root.
4. bazel-remote auth: `authMethod` = `access_key` (default) | `iam_role` | `aws_credentials_file`,
   with `iamRoleEndpoint`, `credentialsFile`, `awsProfile`; none of them in the fingerprint. Fail
   early when `access_key` lacks the environment variables (names only in the message).
5. Release: `maven-publish` to GitHub Packages for the subproject (shared repo config in the root
   build), version without `-SNAPSHOT`, README without the experimental/MVP label.
6. E2E (`gradle-bazel-plugin/src/e2eTest`): minimal Bazel workspace with pure Starlark rules
   (genrule file, tree artifact, executable with runfiles). Scenarios: build + UP-TO-DATE + source,
   BUILD and .bzl edits; unrelated edit stays UP-TO-DATE; Gradle build cache restore; configuration
   cache reuse; tree artifact; runfiles executable runs from the Gradle output; managed bazel-remote
   + MinIO upload, remote cache hit from a fresh output base, read-only writes nothing;
   `aws_credentials_file` auth; broken BUILD fails closed. Skips without bazelisk/bazel-remote/Docker.
7. CI: `e2e` job with `jdx/mise-action` running `./gradlew e2eTest` (root MinIO suite too).

Check: `./gradlew build` and `./gradlew e2eTest` pass locally on Colima; the CI e2e job passes.
