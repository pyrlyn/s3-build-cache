package dev.pyrlyn.gradlebazel

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.*
import java.io.File
import javax.inject.Inject

/**
 * Runs `bazel build` for explicit target labels and copies each target's default output files
 * and tree artifacts (resolved with `bazel cquery --output=files`), optionally with runfiles,
 * into [outputDirectory].
 *
 * Local sources and package definitions are discovered before Gradle snapshots inputs.
 * [inputFiles] supplies additional dependencies not represented in the Bazel graph.
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
    /** Discover main-workspace source and package definition files before up-to-date/cache checks. */
    @get:Input abstract val discoverSourceInputs: Property<Boolean>
    private class Discovery(val paths: List<String>) {
        val files = paths.toSet()
        val dirs = paths.flatMap { p -> p.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.toSet()
    }
    // Memoized per task instance only. Transient: a configuration cache entry must never carry a
    // stale query result, so discovery reruns in every build, also when the entry is reused.
    @Transient private var discoveryMemo: Discovery? = null
    private val discovery: Discovery
        get() = discoveryMemo ?: Discovery(discoverInputs()).also { discoveryMemo = it }

    // Both discovery inputs stay lazy: Gradle inspects input getters while it builds the task graph,
    // and Bazel must only be queried when inputs are snapshotted, never during configuration.
    /** Stable workspace-relative membership, without configuration hashes or external labels. */
    @get:Input val discoveredSourcePaths: Provider<List<String>>
        get() = providers.provider { discovery.paths }
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE)
    val discoveredSourceFiles: FileTree
        get() = objects.fileTree().from(workspaceDirectory).also { tree ->
            // Anchor normalization at the workspace, not each file: equally named files in
            // different packages must retain their path/content association in the cache key.
            // Directories outside discovered paths are pruned, so the walk never enters bazel-* links.
            tree.include { e -> if (e.isDirectory) e.relativePath.pathString in discovery.dirs else e.relativePath.pathString in discovery.files }
        }

    @get:Inject protected abstract val objects: ObjectFactory
    @get:Inject protected abstract val providers: ProviderFactory

    /** Workspace config that always affects results (.bazelversion, MODULE.bazel[.lock], .bazelrc), if present. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) @get:Optional
    val workspaceConfigFiles: List<File>
        get() = listOf(".bazelversion", "MODULE.bazel", "MODULE.bazel.lock", ".bazelrc", "WORKSPACE", "WORKSPACE.bazel")
            .map { File(workspaceDirectory.get().asFile, it) }.filter { it.isFile }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    /**
     * Also copy the `<file>.runfiles` tree next to each executable output, as plain files, so the
     * executable can run from [outputDirectory]. The runfiles MANIFEST is not copied.
     */
    @get:Input abstract val includeRunfiles: Property<Boolean>

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
        discoverSourceInputs.convention(true)
        includeRunfiles.convention(false)
        outputs.cacheIf("assumeHermetic is true") { (it as BazelBuild).assumeHermetic.get() }
    }

    private fun discoverInputs(): List<String> {
        if (!discoverSourceInputs.get()) return emptyList()
        try {
            BazelCommands.validateArgs(startupArgs.get(), buildArgs.get())
            BazelCommands.validate(targets.get(), outputNames.get(), null)
            val ws = workspaceDirectory.get().asFile
            val startup = listOfNotNull(outputUserRoot.orNull?.let { "--output_user_root=$it" }) + startupArgs.get()
            val args = buildArgs.get() + BazelCommands.outputGroupFlags(outputGroups.get())
            val sources = exec(ws, BazelCommands.sourceInputs(executable.get(), startup, args, targets.get()))
            val definitions = exec(ws, BazelCommands.buildFiles(executable.get(), startup, targets.get()))
            return BazelSourceInputs.resolve(ws, (sources + "\n" + definitions).lines())
        } catch (e: Exception) {
            throw GradleException("Bazel source input discovery failed; refusing an incomplete input snapshot. " +
                "Fix the query or set discoverSourceInputs=false and declare all inputs explicitly in inputFiles.", e)
        }
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
        val info = BazelCommands.parseInfo(exec(ws, BazelCommands.info(exe, startup, args)))
        val execRoot = File(info.getValue("execution_root")).toPath()
        val copier = BazelOutputs(listOf(ws.toPath()) + listOf("output_base", "install_base").map { File(info.getValue(it)).toPath() })

        val out = outputDirectory.get().asFile
        out.deleteRecursively(); out.mkdirs()
        for (t in ts) {
            val files = exec(ws, BazelCommands.cqueryFiles(exe, startup, args, t)).lines().filter { it.isNotBlank() }
            if (files.isEmpty()) throw GradleException("$t produced no default output files")
            val rename = names[t]
            if (rename != null && files.size != 1) throw GradleException("$t has ${files.size} outputs; outputNames requires exactly one")
            var runfilesFound = false
            for (rel in files) {
                if (rel.startsWith("/") || rel.split('/').any { it == ".." }) {
                    throw GradleException("refusing to copy $rel: not a path relative to the Bazel execution root")
                }
                val src = execRoot.resolve(rel)
                val dest = File(out, rename ?: src.fileName.toString()).toPath()
                try {
                    val tree = copier.copy(src, dest, "$t output $rel")
                    val runfiles = execRoot.resolve("$rel.runfiles")
                    if (includeRunfiles.get() && !tree && runfiles.toFile().isDirectory) {
                        copier.copyRunfiles(runfiles, dest.resolveSibling("${dest.fileName}.runfiles"), "$t runfiles")
                        runfilesFound = true
                    }
                } catch (e: IllegalStateException) {
                    throw GradleException(e.message!!, e)
                }
            }
            if (includeRunfiles.get() && !runfilesFound) {
                throw GradleException("$t has no runfiles tree: it is not executable, or runfile links are disabled (--nobuild_runfile_links)")
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
