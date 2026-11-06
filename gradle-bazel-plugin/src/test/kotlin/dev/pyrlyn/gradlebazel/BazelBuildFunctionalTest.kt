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

    private fun setup(remote: String = "") {
        val execroot = File(dir, "execroot").apply { mkdirs() }
        File(dir, "fake-bazel").apply {
            writeText(
                """
                #!/bin/sh
                echo "$*" >> "${dir.absolutePath}/calls.log"
                cmd=""; for a in "$@"; do case "${'$'}a" in build|cquery|info) cmd="${'$'}a"; break;; esac; done
                last=""; for a in "$@"; do last="${'$'}a"; done
                case "${'$'}cmd" in
                  info) echo "${execroot.absolutePath}" ;;
                  build) mkdir -p "${execroot.absolutePath}/bazel-out/bin/lib"
                         cat "${dir.absolutePath}/ws/src.kt" > "${execroot.absolutePath}/bazel-out/bin/lib/lib.jar" ;;
                  cquery) echo "bazel-out/bin/lib/lib.jar" ;;
                esac
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }
        File(dir, "ws").mkdirs()
        File(dir, "ws/src.kt").writeText("v1")
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"t\"")
        File(dir, "build.gradle.kts").writeText(
            """
            import dev.pyrlyn.gradlebazel.BazelBuild
            plugins { id("dev.pyrlyn.gradle-bazel") }
            bazel {
                workspaceDirectory.set(layout.projectDirectory.dir("ws"))
                executable.set("${File(dir, "fake-bazel").absolutePath}")
                $remote
            }
            val lib = tasks.register<BazelBuild>("bazelLib") {
                targets.add("//lib:lib")
                outputNames.put("//lib:lib", "lib.jar")
                buildArgs.add("-c"); buildArgs.add("opt")
                inputFiles.from(layout.projectDirectory.dir("ws"))
            }
            val bazelJars by configurations.creating { isCanBeResolved = false; isCanBeConsumed = true }
            artifacts.add("bazelJars", lib.map { it.outputDirectory.file("lib.jar") }) { builtBy(lib) }
            """.trimIndent()
        )
    }

    private fun run(vararg args: String) = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withArguments(*args, "--stacktrace")

    @Test fun `builds, exports artifact, is up to date and reruns on input change`() {
        setup("remoteCacheUrl.set(\"http://127.0.0.1:1\")")
        val r1 = run("bazelLib").build()
        assertEquals(TaskOutcome.SUCCESS, r1.task(":bazelLib")!!.outcome)
        val out = File(dir, "build/bazel/bazelLib/lib.jar")
        assertEquals("v1", out.readText())
        val calls = File(dir, "calls.log").readText()
        assertTrue(calls.contains("build -c opt --remote_cache=http://127.0.0.1:1 --noremote_upload_local_results -- //lib:lib"), calls)
        assertTrue(calls.contains("cquery -c opt --output=files -- //lib:lib"), calls)

        assertEquals(TaskOutcome.UP_TO_DATE, run("bazelLib").build().task(":bazelLib")!!.outcome)

        File(dir, "ws/src.kt").writeText("v2")
        assertEquals(TaskOutcome.SUCCESS, run("bazelLib").build().task(":bazelLib")!!.outcome)
        assertEquals("v2", out.readText())
    }

    @Test fun `invalid config fails with clear message`() {
        setup("remoteCacheUrl.set(\"s3://bucket/prefix\")")
        val r = run("bazelLib").buildAndFail()
        assertTrue(r.output.contains("Bazel cannot talk to s3:// directly"), r.output)
    }
}
