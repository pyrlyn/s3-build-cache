# s3-build-cache — agent instructions

If an `AGENTS.md` or `CLAUDE.md` exists higher in the directory tree, follow it too; on conflict, ask the creator.

- Tasks live in `plan/` (one folder per task id); approved but unplanned work in `roadmap.md`, unapproved ideas in `ideas.md`.
- Tools and packages are listed in `toolchain.md`; e2e tools (bazelisk, bazel-remote) are pinned in `mise.toml`.
- Two independent Gradle plugins: the root project (`dev.pyrlyn.s3-build-cache`) and `gradle-bazel-plugin/` (`dev.pyrlyn.gradle-bazel`). A change to one must not alter the other's behavior.
- Fast checks: `./gradlew build`. Slow checks: `./gradlew e2eTest` (Docker via Colima on macOS; the Bazel suite also needs `mise install`).
- Credentials only through environment variables or files outside the repository; never in arguments, logs, task inputs or test fixtures that are committed.
