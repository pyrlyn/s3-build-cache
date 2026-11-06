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
    /** Build flags (e.g. `-c opt`, `--config=ci`). Fingerprinted; also passed to info/cquery. */
    @get:Input abstract val buildArgs: ListProperty<String>
    /** `--output_groups=` value(s) used for both build and cquery. Empty = default outputs. */
    @get:Input abstract val outputGroups: ListProperty<String>
    /** Startup options other than the output root. Fingerprinted. */
    @get:Input abstract val startupArgs: ListProperty<String>
    /** `--output_user_root`: machine-local location only, normalized out of the fingerprint. */
    @get:Internal abstract val outputUserRoot: Property<String>
    @get:Internal abstract val workspaceDirectory: DirectoryProperty
    /** Workspace location relative to the root project (portable across machines). */
    @get:Internal abstract val rootDirectory: DirectoryProperty
    @get:Input val workspacePath: String
        get() = rootDirectory.get().asFile.toPath().relativize(workspaceDirectory.get().asFile.toPath()).toString().replace('\\', '/')
    /** Directories are fingerprinted recursively by file content and relative path. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:IgnoreEmptyDirectories
    abstract val inputFiles: ConfigurableFileCollection
    /** Workspace config that always affects results (.bazelversion, MODULE.bazel[.lock], .bazelrc), if present. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
    val workspaceConfigFiles: List<File>
        get() = listOf(".bazelversion", "MODULE.bazel", "MODULE.bazel.lock", ".bazelrc", "WORKSPACE", "WORKSPACE.bazel")
            .map { File(workspaceDirectory.get().asFile, it) }.filter { it.isFile }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /**
     * Acknowledge that the declared inputs fully determine the outputs. Default false: the task is
     * neither stored in nor restored from the Gradle build cache and Bazel runs without a remote cache.
     */
    @get:Input abstract val assumeHermetic: Property<Boolean>

    /** Non-secret identity of the remote cache (endpoint/bucket/region/prefix/port/ssl). Fingerprinted. */
    @get:Input @get:Optional abstract val remoteCacheIdentity: Property<String>
    @get:Input abstract val remoteUpload: Property<Boolean>
    @get:Internal abstract val remoteCacheUrl: Property<String>
    @get:Internal abstract val bazelRemote: Property<BazelRemoteService>
    @get:Internal abstract val useManagedBazelRemote: Property<Boolean>

    init {
        outputNames.convention(emptyMap())
        buildArgs.convention(emptyList())
        outputGroups.convention(emptyList())
        startupArgs.convention(emptyList())
        remoteUpload.convention(false)
        useManagedBazelRemote.convention(false)
        assumeHermetic.convention(false)
        outputs.cacheIf("assumeHermetic is true") { assumeHermetic.get() }
    }

    @TaskAction
    fun run() {
        val exe = executable.get()
        val ts = targets.get()
        val names = outputNames.get()
        val startup = listOfNotNull(outputUserRoot.orNull?.let { "--output_user_root=$it" }) + startupArgs.get()
        val args = buildArgs.get() + BazelCommands.outputGroupFlags(outputGroups.get())
        val hermetic = assumeHermetic.get()
        try {
            BazelCommands.validateArgs(startupArgs.get(), buildArgs.get())
            if (useManagedBazelRemote.get() && remoteCacheUrl.isPresent) {
                throw IllegalArgumentException("set either bazel.remoteCacheUrl or bazel.bazelRemote.enabled, not both")
            }
        } catch (e: IllegalArgumentException) {
            throw GradleException(e.message!!, e)
        }
        val url = when {
            !hermetic -> null
            useManagedBazelRemote.get() -> bazelRemote.get().ensureStarted()
            else -> remoteCacheUrl.orNull
        }
        if (!hermetic && (useManagedBazelRemote.get() || remoteCacheUrl.isPresent)) {
            logger.lifecycle("{}: assumeHermetic=false, Bazel remote cache disabled for this task", path)
        }
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
