package dev.pyrlyn.gradlebazel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class BazelOutputsTest {
    @TempDir lateinit var dir: File

    private fun file(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs(); writeText(text) }
    private fun link(path: String, target: Path) = File(dir, path).toPath().also {
        Files.createDirectories(it.parent); Files.createSymbolicLink(it, target)
    }
    private val copier get() = File(dir, "base").mkdirs().let { File(dir, "ws").mkdirs() }.let { BazelOutputs(listOf(File(dir, "base").toPath(), File(dir, "ws").toPath())) }

    @Test fun `tree artifacts are copied recursively with symlinks dereferenced`() {
        file("ws/src.txt", "source")
        file("base/tree/a.txt", "a")
        file("base/tree/sub/b.txt", "b")
        link("base/tree/sub/src.txt", File(dir, "ws/src.txt").toPath())
        val out = File(dir, "out/tree").toPath()
        assertTrue(copier.copy(File(dir, "base/tree").toPath(), out, "t"))
        assertEquals("a", out.resolve("a.txt").toFile().readText())
        assertEquals("b", out.resolve("sub/b.txt").toFile().readText())
        assertFalse(Files.isSymbolicLink(out.resolve("sub/src.txt")))
        assertEquals("source", out.resolve("sub/src.txt").toFile().readText())
    }

    @Test fun `executable bit survives and copies are writable`() {
        val exe = file("base/bin/tool", "#!/bin/sh\n").apply { setExecutable(true); setWritable(false) }
        val out = File(dir, "out/tool")
        assertFalse(copier.copy(exe.toPath(), out.toPath(), "t"))
        assertTrue(out.canExecute() && out.canWrite())
    }

    @Test fun `runfiles skip the absolute-path manifest`() {
        file("base/bin/tool.runfiles/MANIFEST", "_main/data /abs/path")
        file("base/bin/tool.runfiles/_main/data.txt", "data")
        val out = File(dir, "out/tool.runfiles").toPath()
        copier.copyRunfiles(File(dir, "base/bin/tool.runfiles").toPath(), out, "r")
        assertEquals("data", out.resolve("_main/data.txt").toFile().readText())
        assertFalse(Files.exists(out.resolve("MANIFEST")))
    }

    @Test fun `escapes, dangling links, cycles and collisions fail closed`() {
        file("secret.txt", "host secret")
        link("base/tree/leak.txt", File(dir, "secret.txt").toPath())
        assertTrue(assertThrows<IllegalStateException> { copier.copy(File(dir, "base/tree").toPath(), File(dir, "o1").toPath(), "t") }
            .message!!.contains("outside the Bazel workspace"))
        Files.delete(File(dir, "base/tree/leak.txt").toPath())

        link("base/tree/dangling", File(dir, "base/missing").toPath())
        assertTrue(assertThrows<IllegalStateException> { copier.copy(File(dir, "base/tree").toPath(), File(dir, "o2").toPath(), "t") }
            .message!!.contains("dangling"))
        Files.delete(File(dir, "base/tree/dangling").toPath())

        link("base/tree/loop", File(dir, "base/tree").toPath())
        assertTrue(assertThrows<IllegalStateException> { copier.copy(File(dir, "base/tree").toPath(), File(dir, "o3").toPath(), "t") }
            .message!!.contains("cycle"))
        Files.delete(File(dir, "base/tree/loop").toPath())

        file("base/one.txt", "1")
        file("o4", "existing")
        assertTrue(assertThrows<IllegalStateException> { copier.copy(File(dir, "base/one.txt").toPath(), File(dir, "o4").toPath(), "t") }
            .message!!.contains("collision"))
    }
}
