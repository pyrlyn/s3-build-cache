package dev.pyrlyn.gradlebazel

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Copies Bazel outputs into the Gradle output directory as plain files. Symlinks are dereferenced,
 * because Gradle outputs (and build cache entries) must not point back into a machine-local
 * Bazel output base. Every resolved path must stay under [allowedRoots]: a rule could otherwise
 * plant a symlink to an arbitrary host file and have it exported as a task output.
 */
internal class BazelOutputs(allowedRoots: List<Path>) {
    private val roots = allowedRoots.map { it.toRealPath() }

    private fun resolve(path: Path, what: String): Path {
        val real = try {
            path.toRealPath()
        } catch (e: IOException) {
            throw IllegalStateException("$what does not exist or is a dangling symlink: $path", e)
        }
        check(roots.any { real.startsWith(it) }) {
            "$what resolves outside the Bazel workspace and output base: $path -> $real"
        }
        return real
    }

    /** Copies a file or, for a tree artifact, a directory. Returns true when [src] was a directory. */
    fun copy(src: Path, dest: Path, what: String): Boolean {
        val real = resolve(src, what)
        check(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "output name collision: ${dest.fileName}; use outputNames" }
        return if (Files.isDirectory(real)) {
            copyTree(real, dest, what, emptySet(), skipTopLevel = emptySet())
            true
        } else {
            copyFile(real, dest)
            false
        }
    }

    /** Runfiles trees are symlink farms; their top-level MANIFEST lists absolute machine paths and is skipped. */
    fun copyRunfiles(src: Path, dest: Path, what: String) {
        val real = resolve(src, what)
        check(Files.isDirectory(real)) { "$what is not a directory: $src" }
        check(!Files.exists(dest, LinkOption.NOFOLLOW_LINKS)) { "output name collision: ${dest.fileName}" }
        copyTree(real, dest, what, emptySet(), skipTopLevel = setOf("MANIFEST"))
    }

    private fun copyTree(dir: Path, dest: Path, what: String, ancestors: Set<Path>, skipTopLevel: Set<String>) {
        check(dir !in ancestors) { "$what contains a symlink cycle at $dir" }
        Files.createDirectories(dest)
        val entries = Files.list(dir).use { s -> s.sorted().toList() }
        for (entry in entries) {
            val name = entry.fileName.toString()
            if (name in skipTopLevel) continue
            val real = resolve(entry, "$what entry $name")
            val target = dest.resolve(name)
            // plusElement: Path is Iterable<Path>, so `+` would add its name components instead.
            if (Files.isDirectory(real)) copyTree(real, target, what, ancestors.plusElement(dir), emptySet())
            else copyFile(real, target)
        }
    }

    private fun copyFile(real: Path, dest: Path) {
        check(Files.isRegularFile(real)) { "not a regular file: $real" }
        Files.createDirectories(dest.parent)
        // COPY_ATTRIBUTES keeps the executable bit; Bazel outputs are read-only, Gradle must be able to delete them.
        Files.copy(real, dest, StandardCopyOption.COPY_ATTRIBUTES)
        dest.toFile().setWritable(true)
    }
}
