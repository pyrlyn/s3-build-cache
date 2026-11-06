package dev.pyrlyn.gradlebazel

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class GradleBazelExtension {
    abstract val workspaceDirectory: DirectoryProperty
    /** Defaults to `bazelisk` (honours .bazelversion). */
    abstract val executable: Property<String>
    /** Externally managed Bazel remote cache (http/https/grpc/grpcs). Ignored when bazelRemote is enabled. */
    abstract val remoteCacheUrl: Property<String>
    /** Upload locally built results to the remote cache (typically CI only). Default false = read-only. */
    abstract val remoteUpload: Property<Boolean>

    @get:org.gradle.api.tasks.Nested abstract val bazelRemote: BazelRemoteExtension
    fun bazelRemote(action: org.gradle.api.Action<BazelRemoteExtension>) = action.execute(bazelRemote)
}

/** Opt-in, per-build bazel-remote process proxying to the same S3-compatible bucket (separate prefix). */
abstract class BazelRemoteExtension {
    abstract val enabled: Property<Boolean>
    abstract val executable: Property<String>
    abstract val cacheDirectory: DirectoryProperty
    abstract val maxSizeGb: Property<Int>
    abstract val port: Property<Int>
    /** host[:port], e.g. `s3.amazonaws.com` or `localhost:9000`. */
    abstract val s3Endpoint: Property<String>
    abstract val s3Bucket: Property<String>
    /** Keep distinct from the Gradle cache prefix: the key formats are unrelated. */
    abstract val s3Prefix: Property<String>
    abstract val s3Region: Property<String>
    abstract val disableSsl: Property<Boolean>
}

class GradleBazelPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val ext = project.extensions.create("bazel", GradleBazelExtension::class.java)
        ext.workspaceDirectory.convention(project.layout.projectDirectory)
        ext.executable.convention("bazelisk")
        ext.remoteUpload.convention(false)
        val br = ext.bazelRemote
        br.enabled.convention(false)
        br.executable.convention("bazel-remote")
        br.cacheDirectory.convention(project.layout.buildDirectory.dir("bazel-remote/cache"))
        br.maxSizeGb.convention(5)
        br.port.convention(9090)
        br.s3Prefix.convention("bazel-remote")
        br.disableSsl.convention(false)

        val service = project.gradle.sharedServices.registerIfAbsent(
            "bazelRemote-" + project.path, BazelRemoteService::class.java,
        ) { spec ->
            spec.maxParallelUsages.set(1)
            spec.parameters.executable.set(br.executable)
            spec.parameters.cacheDir.set(br.cacheDirectory.map { it.asFile.absolutePath })
            spec.parameters.maxSizeGb.set(br.maxSizeGb)
            spec.parameters.port.set(br.port)
            spec.parameters.s3Endpoint.set(br.s3Endpoint)
            spec.parameters.s3Bucket.set(br.s3Bucket)
            spec.parameters.s3Prefix.set(br.s3Prefix)
            spec.parameters.s3Region.set(br.s3Region)
            spec.parameters.disableSsl.set(br.disableSsl)
            spec.parameters.logFile.set(project.layout.buildDirectory.file("bazel-remote/bazel-remote.log").map { it.asFile.absolutePath })
        }

        project.tasks.withType(BazelBuild::class.java).configureEach { t ->
            t.group = "bazel"
            t.executable.convention(ext.executable)
            t.workspaceDirectory.convention(ext.workspaceDirectory)
            t.outputDirectory.convention(project.layout.buildDirectory.dir("bazel/${t.name}"))
            t.remoteCacheUrl.convention(ext.remoteCacheUrl)
            t.remoteUpload.convention(ext.remoteUpload)
            t.useManagedBazelRemote.convention(br.enabled)
            t.bazelRemote.convention(service)
            t.usesService(service)
        }
    }
}
