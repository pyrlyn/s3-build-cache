package dev.pyrlyn.gradlebazel

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** Real Bazel (via bazelisk and the workspace's .bazelversion); no remote cache. */
class BazelE2ETest {
    @TempDir lateinit var dir: File
    private val projects = mutableListOf<E2EProject>()

    private fun project(name: String = "p") = E2EProject(File(dir, name)).also { projects += it }

    @AfterEach fun shutdown() = projects.forEach { it.shutdown() }

    @Test fun `genrule output is exported, up to date, and reruns only on graph edits`() {
        val p = project()
        assertEquals(TaskOutcome.SUCCESS, p.outcome("gen"))
        assertEquals("hello v1\ngenerated\n", p.output("gen/gen.txt").readText())
        assertEquals(TaskOutcome.UP_TO_DATE, p.outcome("gen"))

        File(p.ws, "gen/unrelated.txt").writeText("changed, but not in the graph")
        assertEquals(TaskOutcome.UP_TO_DATE, p.outcome("gen"), "files outside the target graph are not inputs")

        File(p.ws, "gen/src.txt").writeText("hello v2\n")
        assertEquals(TaskOutcome.SUCCESS, p.outcome("gen"), "source edit")
        assertEquals("hello v2\ngenerated\n", p.output("gen/gen.txt").readText())

        File(p.ws, "gen/BUILD.bazel").appendText("\n# edited\n")
        assertEquals(TaskOutcome.SUCCESS, p.outcome("gen"), "BUILD edit")
        assertEquals(TaskOutcome.UP_TO_DATE, p.outcome("gen"))
    }

    @Test fun `tree artifact is exported as a directory and a loaded bzl edit reruns it`() {
        val p = project()
        assertEquals(TaskOutcome.SUCCESS, p.outcome("tree"))
        assertEquals("tree a\n", p.output("tree/tree/a.txt").readText())
        assertEquals("nested\n", p.output("tree/tree/sub/nested.txt").readText())
        assertEquals(TaskOutcome.UP_TO_DATE, p.outcome("tree"))

        File(p.ws, "defs/rules.bzl").appendText("\n# edited\n")
        assertEquals(TaskOutcome.SUCCESS, p.outcome("tree"), "loaded .bzl edit")
    }

    @Test fun `executable runs from the Gradle output with copied runfiles`() {
        val p = project()
        assertEquals(TaskOutcome.SUCCESS, p.outcome("tool"))
        val tool = p.output("tool/tool")
        val proc = ProcessBuilder(tool.absolutePath).redirectErrorStream(true).start()
        assertEquals("runfile data", proc.inputStream.bufferedReader().readText().trim())
        assertEquals(0, proc.waitFor())
        assertFalse(p.output("tool/tool.runfiles/MANIFEST").exists(), "manifest holds absolute machine paths")
        Files.walk(p.output("tool").toPath()).use { s ->
            assertTrue(s.noneMatch { Files.isSymbolicLink(it) }, "outputs must not point into the Bazel output base")
        }
    }

    @Test fun `hermetic outputs are restored from the build cache, also at another project path`() {
        val a = project("a")
        assertEquals(TaskOutcome.SUCCESS, a.outcome("gen", "--build-cache"))
        File(a.dir, "build").deleteRecursively()
        assertEquals(TaskOutcome.FROM_CACHE, a.outcome("gen", "--build-cache"))
        assertEquals("hello v1\ngenerated\n", a.output("gen/gen.txt").readText())

        val b = project("b")
        assertEquals(TaskOutcome.FROM_CACHE, b.outcome("gen", "--build-cache"), "cache key is relocatable")
    }

    @Test fun `configuration cache is reused and sources are still rediscovered`() {
        val p = project()
        val first = p.build("gen", "--configuration-cache")
        assertTrue(first.output.contains("Configuration cache entry stored"), first.output)
        val second = p.build("gen", "--configuration-cache")
        assertTrue(second.output.contains("Configuration cache entry reused"), second.output)
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":gen")!!.outcome)

        File(p.ws, "gen/src.txt").writeText("cc edit\n")
        val third = p.build("gen", "--configuration-cache")
        assertTrue(third.output.contains("Configuration cache entry reused"), third.output)
        assertEquals(TaskOutcome.SUCCESS, third.task(":gen")!!.outcome)
        assertEquals("cc edit\ngenerated\n", p.output("gen/gen.txt").readText())
    }

    @Test fun `a broken BUILD file fails closed before any build`() {
        val p = project()
        assertEquals(TaskOutcome.SUCCESS, p.outcome("gen"))
        File(p.ws, "gen/BUILD.bazel").writeText("genrule(name = \n")
        val r = p.fail("gen")
        assertTrue(r.output.contains("refusing an incomplete input snapshot"), r.output)
    }
}
