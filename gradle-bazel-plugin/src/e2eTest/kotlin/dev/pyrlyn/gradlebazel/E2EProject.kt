package dev.pyrlyn.gradlebazel

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/** Locates real tools: a system property wins, otherwise PATH (mise puts them there, see mise.toml). */
object E2ETools {
    private fun find(property: String, name: String): String? =
        System.getProperty(property)?.takeIf { File(it).canExecute() }
            ?: System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, name) }.firstOrNull { it.canExecute() }?.absolutePath

    val bazelisk: String? by lazy { find("e2e.bazelisk", "bazelisk") }
    val bazelRemote: String? by lazy { find("e2e.bazelRemote", "bazel-remote") }

    fun requireBazelisk(): String = bazelisk.also { assumeTrue(it != null, "bazelisk not found (run `mise install`)") }!!
    fun requireBazelRemote(): String = bazelRemote.also { assumeTrue(it != null, "bazel-remote not found (run `mise install`)") }!!

    /** Shared and short: Bazel keeps its repository cache here, and macOS limits socket path lengths. */
    val outputUserRoot: File = File(System.getProperty("java.io.tmpdir"), "gradle-bazel-e2e").canonicalFile

    val workspaceTemplate = File(System.getProperty("e2e.workspace") ?: "src/e2eTest/workspace")

    fun freePort(): Int = ServerSocket(0).use { it.localPort }
}

/**
 * A throwaway Gradle project around a copy of the e2e Bazel workspace. Tasks: `gen` (genrule),
 * `tree` (tree artifact), `tool` (executable with runfiles), all hermetic.
 */
class E2EProject(val dir: File, private val bazelExtension: String = "", private val env: Map<String, String> = emptyMap()) {
    private val bazelisk = E2ETools.requireBazelisk()
    val ws = File(dir, "ws")

    init {
        E2ETools.workspaceTemplate.copyRecursively(ws, overwrite = true)
        File(dir, "settings.gradle.kts").writeText(
            """
            rootProject.name = "e2e"
            buildCache { local { directory = file(providers.gradleProperty("cacheDir").orElse("${'$'}rootDir/../gradle-cache").get()) } }
            """.trimIndent()
        )
        File(dir, "build.gradle.kts").writeText(
            """
            import dev.pyrlyn.gradlebazel.BazelBuild
            plugins { id("dev.pyrlyn.gradle-bazel") }
            bazel {
                workspaceDirectory.set(layout.projectDirectory.dir("ws"))
                executable.set("$bazelisk")
                $bazelExtension
            }
            fun bazel(name: String, label: String, configure: BazelBuild.() -> Unit = {}) = tasks.register<BazelBuild>(name) {
                targets.add(label)
                outputUserRoot.set("${E2ETools.outputUserRoot}")
                // Idle servers exit on their own; tests also shut them down explicitly.
                startupArgs.add("--max_idle_secs=120")
                assumeHermetic.set(true)
                configure()
            }
            bazel("gen", "//gen:gen")
            bazel("tree", "//tree:tree")
            bazel("tool", "//tool:tool") { includeRunfiles.set(true) }
            """.trimIndent()
        )
    }

    fun runner(vararg args: String): GradleRunner = GradleRunner.create().withProjectDir(dir).withPluginClasspath()
        .withEnvironment(System.getenv().filterKeys { !it.startsWith("S3_CACHE_") } + env)
        .withArguments(*args, "--stacktrace")

    fun build(vararg args: String): BuildResult = runner(*args).build()
    fun fail(vararg args: String): BuildResult = runner(*args).buildAndFail()
    fun outcome(task: String, vararg args: String): TaskOutcome = build(task, *args).task(":$task")!!.outcome
    fun output(path: String) = File(dir, "build/bazel/$path")

    /** Stops this workspace's Bazel server so test runs leave no processes behind. */
    fun shutdown() {
        if (!ws.isDirectory) return
        val p = ProcessBuilder(bazelisk, "--output_user_root=${E2ETools.outputUserRoot}", "shutdown")
            .directory(ws).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        if (!p.waitFor(60, TimeUnit.SECONDS)) p.destroyForcibly()
    }
}
