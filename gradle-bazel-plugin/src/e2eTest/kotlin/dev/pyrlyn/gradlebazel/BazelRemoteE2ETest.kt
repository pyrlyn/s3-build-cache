package dev.pyrlyn.gradlebazel

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.MinIOContainer
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.io.File
import java.net.URI

/** Plugin-managed bazel-remote proxying to a real MinIO (Testcontainers), driven by real Bazel. */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BazelRemoteE2ETest {
    // Same image and override as the root project's MinIO e2e suite.
    private val minioImage = System.getProperty("e2e.minioImage") ?: System.getenv("MINIO_IMAGE") ?: "cgr.dev/chainguard/minio:latest"
    private val minio = MinIOContainer(DockerImageName.parse(minioImage).asCompatibleSubstituteFor("minio/minio"))
    private lateinit var s3: S3Client
    private val bucket = "bazel-e2e"

    @TempDir lateinit var dir: File
    private val projects = mutableListOf<E2EProject>()

    @BeforeAll fun start() {
        E2ETools.requireBazelisk()
        E2ETools.requireBazelRemote()
        minio.start()
        s3 = S3Client.builder().endpointOverride(URI(minio.s3URL)).region(Region.US_EAST_1).forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.userName, minio.password)))
            .build()
        s3.createBucket { it.bucket(bucket) }
    }

    @AfterEach fun shutdown() = projects.forEach { it.shutdown() }
    @AfterAll fun stop() { if (::s3.isInitialized) s3.close(); minio.stop() }

    private fun keys(prefix: String): List<String> =
        s3.listObjectsV2Paginator { it.bucket(bucket).prefix(prefix) }.contents().map { it.key() }

    private fun bazelRemote(prefix: String, upload: Boolean, auth: String = "") = """
        remoteUpload.set($upload)
        bazelRemote {
            enabled.set(true)
            executable.set("${E2ETools.bazelRemote}")
            s3Endpoint.set("${minio.host}:${minio.getMappedPort(9000)}")
            s3Bucket.set("$bucket")
            s3Region.set("us-east-1")
            s3Prefix.set("$prefix")
            disableSsl.set(true)
            maxSizeGb.set(1)
            port.set(${E2ETools.freePort()})
            $auth
        }
    """.trimIndent()

    private fun project(name: String, extension: String, env: Map<String, String> = emptyMap()) =
        E2EProject(File(dir, name), extension, env).also { projects += it }

    private val accessKeyEnv get() = mapOf("S3_CACHE_ACCESS_KEY_ID" to minio.userName, "S3_CACHE_SECRET_ACCESS_KEY" to minio.password)

    /** bazel-remote uploads asynchronously; the queue is drained before it exits on SIGTERM. */
    private fun awaitKeys(prefix: String, minimum: Int): List<String> {
        val deadline = System.nanoTime() + 30_000_000_000
        while (true) {
            val k = keys(prefix)
            if (k.size >= minimum || System.nanoTime() > deadline) return k
            Thread.sleep(250)
        }
    }

    @Test fun `upload from one workspace, remote cache hit in a fresh one, read-only writes nothing`() {
        val prefix = "share"
        val writer = project("writer", bazelRemote(prefix, upload = true), accessKeyEnv)
        val w = writer.build("gen", "tree")
        assertTrue(w.output.contains("auth access_key, read-write"), w.output)
        val stored = awaitKeys("$prefix/", 2)
        assertTrue(stored.any { it.startsWith("$prefix/ac/") }, stored.toString())
        assertTrue(stored.any { it.startsWith("$prefix/cas.v2/") }, stored.toString())

        // Fresh project path => fresh Bazel output base and an empty bazel-remote disk tier.
        val credentials = File(dir, "aws-credentials").apply {
            writeText("[e2e]\naws_access_key_id = ${minio.userName}\naws_secret_access_key = ${minio.password}\n")
        }
        val reader = project("reader", bazelRemote(prefix, upload = false,
            "authMethod.set(\"aws_credentials_file\"); credentialsFile.set(file(\"${credentials.absolutePath}\")); awsProfile.set(\"e2e\")"))
        val before = keys("$prefix/")
        val r = reader.build("gen", "tree")
        assertTrue(r.output.contains("auth aws_credentials_file, read-only"), r.output)
        assertTrue(Regex("""\d+ remote cache hit""").containsMatchIn(r.output), r.output)
        assertEquals("hello v1\ngenerated\n", reader.output("gen/gen.txt").readText())
        assertEquals("nested\n", reader.output("tree/tree/sub/nested.txt").readText())

        File(reader.ws, "gen/src.txt").writeText("built locally, never uploaded\n")
        assertEquals(TaskOutcome.SUCCESS, reader.outcome("gen"))
        Thread.sleep(2000)
        assertEquals(before.sorted(), keys("$prefix/").sorted(), "read-only mode must not write to S3")
    }

    @Test fun `non-hermetic task never talks to the remote cache`() {
        val prefix = "nonhermetic"
        val p = project("nonhermetic", bazelRemote(prefix, upload = true), accessKeyEnv)
        File(p.dir, "build.gradle.kts").appendText("\ntasks.named<BazelBuild>(\"gen\") { assumeHermetic.set(false) }\n")
        val r = p.build("gen")
        assertTrue(r.output.contains("assumeHermetic=false, Bazel remote cache disabled"), r.output)
        assertFalse(r.output.contains("Starting bazel-remote"), r.output)
        assertEquals(emptyList<String>(), keys("$prefix/"))
    }

    @Test fun `access_key auth without credentials fails before starting bazel-remote`() {
        val p = project("nokeys", bazelRemote("nokeys", upload = true))
        val r = p.fail("gen")
        assertTrue(r.output.contains("needs S3_CACHE_ACCESS_KEY_ID and S3_CACHE_SECRET_ACCESS_KEY"), r.output)
        assertFalse(r.output.contains(minio.password), "secrets never appear in output")
    }
}
