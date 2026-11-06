package dev.pyrlyn.gradlebazel

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Runs `bazel build` for explicit target labels and copies each target's default output files
 * (resolved with `bazel cquery --output=files`) into [outputDirectory].
 *
 * Gradle up-to-date checks and build caching are only as correct as [inputFiles]: declare every
 * source/BUILD/MODULE file the targets depend on.
 */
@CacheableTask
abstract class BazelBuild : DefaultTask() {
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val targets: ListProperty<String>
    /** Optional label -> file name rename for targets that produce exactly one file. */
    @get:Input abstract val outputNames: MapProperty<String, String>
    /** Build flags (e.g. `-c opt`, `--config=ci`). Part of the fingerprint; also passed to cquery. */
    @get:Input abstract val buildArgs: ListProperty<String>
    /** Startup options (e.g. `--output_user_root=...`). Machine-local, not fingerprinted. */
    @get:Internal abstract val startupArgs: ListProperty<String>
    @get:Internal abstract val workspaceDirectory: DirectoryProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val inputFiles: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /** Remote cache endpoint (externally managed or the plugin-managed bazel-remote). Not fingerprinted. */
    @get:Internal abstract val remoteCacheUrl: Property<String>
    @get:Internal abstract val remoteUpload: Property<Boolean>
    @get:Internal abstract val bazelRemote: Property<BazelRemoteService>
    @get:Internal abstract val useManagedBazelRemote: Property<Boolean>

    init {
        outputNames.convention(emptyMap())
        buildArgs.convention(emptyList())
        startupArgs.convention(emptyList())
        remoteUpload.convention(false)
        useManagedBazelRemote.convention(false)
    }

    @TaskAction
    fun run() {
        val exe = executable.get()
        val ts = targets.get()
        val names = outputNames.get()
        val startup = startupArgs.get()
        val args = buildArgs.get()
        val url = if (useManagedBazelRemote.get()) bazelRemote.get().ensureStarted() else remoteCacheUrl.orNull
        try {
            BazelCommands.validate(ts, names, url)
        } catch (e: IllegalArgumentException) {
            throw GradleException(e.message!!, e)
        }
        val ws = workspaceDirectory.get().asFile
        exec(ws, BazelCommands.build(exe, startup, args, BazelCommands.remoteFlags(url, remoteUpload.get()), ts))
        val execRoot = File(exec(ws, BazelCommands.executionRoot(exe, startup, args)).trim()).canonicalFile

        val out = outputDirectory.get().asFile
        out.deleteRecursively(); out.mkdirs()
        for (t in ts) {
            val files = exec(ws, BazelCommands.cqueryFiles(exe, startup, args, t)).lines().filter { it.isNotBlank() }
            if (files.isEmpty()) throw GradleException("$t produced no default output files")
            val rename = names[t]
            if (rename != null && files.size != 1) throw GradleException("$t has ${files.size} outputs; outputNames requires exactly one")
            for (rel in files) {
                if (rel.startsWith("/") || rel.split('/').any { it == ".." }) {
                    throw GradleException("refusing to copy $rel: not a path relative to the Bazel execution root")
                }
                val src = File(execRoot, rel)
                if (src.isDirectory) throw GradleException("$t output $rel is a tree artifact (directory); not supported in this MVP")
                if (!src.isFile) throw GradleException("$t output $rel does not exist after build")
                val dest = File(out, rename ?: src.name)
                if (dest.exists()) throw GradleException("output file name collision: ${dest.name}; use outputNames")
                Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
                dest.setWritable(true)
            }
        }
    }

    private fun exec(dir: File, cmd: List<String>): String {
        logger.info("bazel: {}", cmd.joinToString(" "))
        val p = ProcessBuilder(cmd).directory(dir).start()
        // Forward Bazel's progress/summary (stderr) to the Gradle client; the daemon's own stderr is not visible.
        val err = Thread {
            p.errorStream.bufferedReader().forEachLine { line ->
                if (line.startsWith("INFO: ") || line.startsWith("ERROR: ") || line.startsWith("WARNING: ")) {
                    logger.lifecycle("[bazel] {}", line)
                } else logger.info("[bazel] {}", line)
            }
        }.apply { isDaemon = true; start() }
        val stdout = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        err.join()
        if (code != 0) throw GradleException("Bazel command failed (exit $code): ${cmd.take(2 + startupArgs.get().size).joinToString(" ")} ...")
        return stdout
    }
}
