package dev.pyrlyn.gradlebazel

import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.api.logging.Logging
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Starts a local bazel-remote S3 proxy lazily (first Bazel task that needs it) and stops it when the
 * Gradle build finishes. Nothing persistent is deployed.
 */
abstract class BazelRemoteService : BuildService<BazelRemoteService.Params>, AutoCloseable {
    interface Params : BuildServiceParameters {
        val executable: Property<String>
        val cacheDir: Property<String>
        val maxSizeGb: Property<Int>
        val port: Property<Int>
        val s3Endpoint: Property<String>
        val s3Bucket: Property<String>
        val s3Prefix: Property<String>
        val s3Region: Property<String>
        val disableSsl: Property<Boolean>
        val logFile: Property<String>
    }

    private val log = Logging.getLogger(BazelRemoteService::class.java)
    private var process: Process? = null

    @Synchronized
    fun ensureStarted(): String {
        val p = parameters
        val s = BazelRemoteSettings(
            p.executable.get(), p.cacheDir.get(), p.maxSizeGb.get(), p.port.get(),
            p.s3Endpoint.get(), p.s3Bucket.get(), p.s3Prefix.get(), p.s3Region.orNull, p.disableSsl.get(),
        )
        if (process?.isAlive == true) return s.url
        File(s.cacheDir).mkdirs()
        val logFile = File(p.logFile.get()).also { it.parentFile.mkdirs() }
        val pb = ProcessBuilder(s.command()).redirectErrorStream(true).redirectOutput(logFile)
        pb.environment().putAll(BazelRemoteSettings.credentialEnv(System.getenv()))
        log.lifecycle("Starting bazel-remote S3 proxy on ${s.url} (bucket ${s.s3Bucket}, prefix ${s.s3Prefix})")
        val proc = pb.start()
        process = proc
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) error("bazel-remote exited early (code ${proc.exitValue()}); see $logFile")
            if (healthy(s.url)) return s.url
            Thread.sleep(200)
        }
        close()
        error("bazel-remote did not become ready on ${s.url}; see $logFile")
    }

    private fun healthy(url: String): Boolean = runCatching {
        // The empty CAS blob always exists on a running bazel-remote.
        val c = URI("$url/cas/e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855").toURL()
            .openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 500; c.readTimeout = 500
        c.responseCode == 200
    }.getOrDefault(false)

    @Synchronized
    override fun close() {
        val proc = process ?: return
        proc.destroy() // SIGTERM: bazel-remote shuts down gracefully
        if (!proc.waitFor(15, TimeUnit.SECONDS)) proc.destroyForcibly()
        process = null
    }
}
