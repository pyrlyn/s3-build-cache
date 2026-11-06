package dev.pyrlyn.gradlebazel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class BazelSourceInputsTest {
    @TempDir lateinit var dir: File

    @Test fun `main repository spellings normalize and external downloads are excluded`() {
        File(dir, "pkg").mkdirs()
        File(dir, "pkg/source.kt").writeText("source")
        File(dir, "BUILD.bazel").writeText("build")
        assertEquals(listOf("BUILD.bazel", "pkg/source.kt"), BazelSourceInputs.resolve(dir,
            listOf("//pkg:source.kt (null)", "@//pkg:source.kt (123abc)", "@@//:BUILD.bazel",
                "@@compiler+//:downloaded.jar (null)", "@local_repo//:tool", "")))
    }

    @Test fun `escaping symlinks and directories are rejected`() {
        val ws = File(dir, "ws").apply { mkdirs() }
        val outside = File(dir, "outside.kt").apply { writeText("outside") }
        Files.createSymbolicLink(File(ws, "link.kt").toPath(), outside.toPath())
        assertThrows<IllegalArgumentException> { BazelSourceInputs.resolve(ws, listOf("//:link.kt")) }
        File(ws, "folder").mkdirs()
        assertThrows<IllegalArgumentException> { BazelSourceInputs.resolve(ws, listOf("//:folder")) }
    }
}
