package dev.pyrlyn.gradlebazel

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Uses a fake `bazel` shell script so the suite needs neither Bazel nor S3. */
class BazelBuildFunctionalTest {
    @TempDir lateinit var dir: File

    private fun setup(extension: String = "", task: String = "assumeHermetic.set(true)") {
        val execroot = File(dir, "execroot").apply { mkdirs() }
        File(dir, "fake-bazel").apply {
            writeText(
                """
                #!/bin/sh
                echo "$*" >> "${dir.absolutePath}/calls.log"
                cmd=""; for a in "$@"; do case "${'$'}a" in build|cquery|info) cmd="${'$'}a"; break;; esac; done
                case "${'$'}cmd" in
                  info) echo "${execroot.absolutePath}" ;;
                  build) mkdir -p "${execroot.absolutePath}/bazel-out/bin/lib"
                         cat "${dir.absolutePath}/ws/src/nested/src.kt" > "${execroot.absolutePath}/bazel-out/bin/lib/lib.jar" ;;
                  cquery) echo "bazel-out/bin/lib/lib.jar" ;;
                esac
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        File(dir, "ws/src/nested").mkdirs()
        File(dir, "ws/src/nested/src.kt").writeText("v1")
        File(dir, "ws/MODULE.bazel").writeText("module(name = \"t\")")
        File(dir, "settings.gradle.kts").writeText(
            "rootProject.name = \"t\"\nbuildCache { local { directory = file(\"${File(dir, "gradle-cache").absolutePath}\") } }"
        )
        File(dir, "build.gradle.kts").writeText(
            """
            import dev.pyrlyn.gradlebazel.BazelBuild
            plugins { id("dev.pyrlyn.gradle-bazel") }
            bazel {
                workspaceDirectory.set(layout.projectDirectory.dir("ws"))
                executable.set("${File(dir, "fake-bazel").absolutePath}")
                $extension
            }
            val lib = tasks.register<BazelBuild>("bazelLib") {
                targets.add("//lib:lib")
                outputNames.put("//lib:lib", "lib.jar")
                buildArgs.add("-c"); buildArgs.add("opt")
                outputGroups.add(providers.gradleProperty("og").orElse("default"))
                startupArgs.add(providers.gradleProperty("startup").orElse("--host_jvm_args=-Xmx1g"))
                outputUserRoot.set(providers.gradleProperty("root").orElse("/tmp/root-a"))
                inputFiles.from(layout.projectDirectory.dir("ws/src"))
                $task
            }
            configurations.consumable("bazelJars")
            artifacts.add("bazelJars", lib.map { it.outputDirectory.file("lib.jar") }) { builtBy(lib) }
            """.trimIndent()
        )
    }

    private fun run(vararg args: String) = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    private fun outcome(vararg args: String) = run("bazelLib", *args).build().task(":bazelLib")!!.outcome
    private val calls get() = File(dir, "calls.log").readText()

    @Test fun `forwards args, exports artifact, up to date, reruns on nested input and config changes`() {
        setup("remoteCacheUrl.set(\"http://127.0.0.1:1\")")
        assertEquals(TaskOutcome.SUCCESS, outcome())
        val out = File(dir, "build/bazel/bazelLib/lib.jar")
        assertEquals("v1", out.readText())
        assertTrue(calls.contains("--output_user_root=/tmp/root-a --host_jvm_args=-Xmx1g build -c opt --output_groups=default " +
            "--remote_cache=http://127.0.0.1:1 --noremote_upload_local_results -- //lib:lib"), calls)
        assertTrue(calls.contains("cquery -c opt --output_groups=default --output=files -- //lib:lib"), calls)

        assertEquals(TaskOutcome.UP_TO_DATE, outcome())
        assertEquals(TaskOutcome.UP_TO_DATE, outcome("-Proot=/tmp/root-b"), "output_user_root is normalized out")
        assertEquals(TaskOutcome.SUCCESS, outcome("-Pstartup=--host_jvm_args=-Xmx2g"), "startup args are fingerprinted")
        assertEquals(TaskOutcome.SUCCESS, outcome("-Pog=other"), "output groups are fingerprinted")
        assertEquals(TaskOutcome.SUCCESS, outcome())

        File(dir, "ws/src/nested/src.kt").writeText("v2")
        assertEquals(TaskOutcome.SUCCESS, outcome(), "nested file content is fingerprinted")
        assertEquals("v2", out.readText())
        File(dir, "ws/MODULE.bazel").writeText("module(name = \"t2\")")
        assertEquals(TaskOutcome.SUCCESS, outcome(), "workspace config files are fingerprinted")
    }

    @Test fun `remote cache identity and upload mode are fingerprinted`() {
        setup("""
            remoteCacheUrl.set(providers.gradleProperty("url").orElse("http://127.0.0.1:1"))
            remoteUpload.set(providers.gradleProperty("upload").map { it.toBoolean() }.orElse(false))
        """.trimIndent())
        assertEquals(TaskOutcome.SUCCESS, outcome())
        assertEquals(TaskOutcome.UP_TO_DATE, outcome())
        assertEquals(TaskOutcome.SUCCESS, outcome("-Purl=http://127.0.0.1:2"))
        assertEquals(TaskOutcome.SUCCESS, outcome("-Purl=http://127.0.0.1:2", "-Pupload=true"))
        assertTrue(calls.lines().last { it.contains(" build ") }.let { "--remote_cache=http://127.0.0.1:2" in it && "noremote_upload" !in it })
    }

    @Test fun `hermetic task is restored from Gradle build cache`() {
        setup()
        assertEquals(TaskOutcome.SUCCESS, outcome("--build-cache"))
        File(dir, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, outcome("--build-cache"))
    }

    @Test fun `non-hermetic task is never cached and runs without remote cache`() {
        setup("remoteCacheUrl.set(\"http://127.0.0.1:1\")", task = "")
        val r = run("bazelLib", "--build-cache").build()
        assertEquals(TaskOutcome.SUCCESS, r.task(":bazelLib")!!.outcome)
        assertTrue(r.output.contains("assumeHermetic=false, Bazel remote cache disabled"), r.output)
        assertFalse(calls.contains("remote_cache"), calls)
        File(dir, "build").deleteRecursively()
        assertEquals(TaskOutcome.SUCCESS, outcome("--build-cache"))
    }

    @Test fun `invalid configs fail with clear messages`() {
        setup("remoteCacheUrl.set(\"s3://bucket/prefix\")")
        assertTrue(run("bazelLib").buildAndFail().output.contains("Bazel cannot talk to s3:// directly"))
        setup(task = "assumeHermetic.set(true); buildArgs.add(\"--remote_cache=http://x\")")
        assertTrue(run("bazelLib").buildAndFail().output.contains("buildArgs must not contain"))
        setup("remoteCacheUrl.set(\"http://x\"); bazelRemote { enabled.set(true); s3Bucket.set(\"b\"); s3Endpoint.set(\"h\") }")
        assertTrue(run("bazelLib").buildAndFail().output.contains("not both"))
    }
}
