package dev.pyrlyn.gradlebazel

import java.io.File

/** Maps query labels, never execroot paths, to existing main-workspace files. */
internal object BazelSourceInputs {
    fun resolve(workspace: File, lines: List<String>): List<String> {
        val root = workspace.canonicalFile.toPath()
        return lines.filter { it.isNotBlank() }.mapNotNull { line ->
            // cquery --output=label appends a configuration hash (or "(null)" for sources).
            val label = line.substringBefore(" (").trim()
            val local = when {
                label.startsWith("@@//") -> label.removePrefix("@@")
                label.startsWith("@//") -> label.removePrefix("@")
                label.startsWith("//") -> label
                label.startsWith("@") && label.contains("//") -> return@mapNotNull null // external repository
                else -> error("Unexpected Bazel input label: $label")
            }
            require(local.count { it == ':' } == 1) { "Invalid source label: $label" }
            val pkg = local.removePrefix("//").substringBefore(':')
            val name = local.substringAfter(':')
            val relative = if (pkg.isEmpty()) name else "$pkg/$name"
            require(relative.isNotEmpty() && relative.split('/').none { it in setOf("", ".", "..") } &&
                relative.none { it in "\\*?" }) { "Unsafe source label: $label" }
            val file = File(workspace, relative)
            require(file.canonicalFile.toPath().startsWith(root)) { "Source escapes workspace: $label" }
            require(file.isFile) { "Discovered workspace input is not a file: $label" }
            relative
        }.distinct().sorted()
    }
}
