package dev.pyrlyn.gradlebazel

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BazelCommandsTest {
    @Test fun `build command orders startup, command, flags, remote and targets`() {
        val cmd = BazelCommands.build("bazel", listOf("--output_user_root=/x"), listOf("-c", "opt"),
            BazelCommands.remoteFlags("http://127.0.0.1:9090", upload = false), listOf("//c:hello"))
        assertEquals(listOf("bazel", "--output_user_root=/x", "build", "-c", "opt",
            "--remote_cache=http://127.0.0.1:9090", "--noremote_upload_local_results", "--", "//c:hello"), cmd)
    }

    @Test fun `no remote flags without url, upload omits read-only flag`() {
        assertEquals(emptyList<String>(), BazelCommands.remoteFlags(null, true))
        assertEquals(listOf("--remote_cache=grpc://h:1"), BazelCommands.remoteFlags("grpc://h:1", true))
    }

    @Test fun `cquery reuses build flags`() {
        assertEquals(listOf("bazel", "cquery", "-c", "opt", "--output=files", "--", "//a:b"),
            BazelCommands.cqueryFiles("bazel", emptyList(), listOf("-c", "opt"), "//a:b"))
    }

    @Test fun `discovery uses configured deps and unconfigured build definitions for multiple labels`() {
        val targets = listOf("//a:b", "//c:d")
        assertEquals(listOf("bazel", "--batch", "cquery", "--config=ci", "--output=label", "--",
            "kind(\"source file\", deps(set(\"//a:b\" \"//c:d\")))"),
            BazelCommands.sourceInputs("bazel", listOf("--batch"), listOf("--config=ci"), targets))
        assertEquals(listOf("bazel", "--batch", "query", "--output=label", "--",
            "buildfiles(deps(set(\"//a:b\" \"//c:d\")))"),
            BazelCommands.buildFiles("bazel", listOf("--batch"), targets))
    }

    @Test fun `rejects invalid configs`() {
        assertThrows<IllegalArgumentException> { BazelCommands.validate(emptyList(), emptyMap(), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("hello"), emptyMap(), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//..."), emptyMap(), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//a:all"), emptyMap(), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//a:b"), mapOf("//x:y" to "f"), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//a:b"), mapOf("//a:b" to "../f"), null) }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//a:b"), emptyMap(), "s3://bucket") }
        assertThrows<IllegalArgumentException> { BazelCommands.validate(listOf("//a:b"), emptyMap(), "https://u:p@h") }
        BazelCommands.validate(listOf("//a:b", "@rules//x:y"), mapOf("//a:b" to "b.jar"), "https://cache.example:443")
    }

    @Test fun `bazel-remote command never contains credentials`() {
        val s = BazelRemoteSettings("bazel-remote", "/c", 5, 9090, "localhost:9000", "bkt", "bazel/", "us-east-1", true)
        val cmd = s.command()
        assertTrue("--s3.auth_method=access_key" in cmd)
        assertTrue("--s3.disable_ssl" in cmd)
        assertTrue("--http_address=127.0.0.1:9090" in cmd)
        assertTrue(cmd.none { it.contains("secret", true) || it.contains("access_key_id") })
        assertTrue("--num_uploaders=0" in cmd, "read-only by default")
        assertFalse("--num_uploaders=0" in s.copy(upload = true).command())
        assertFalse(s.identity.contains("AK"))
        assertNotEquals(s.identity, s.copy(s3Prefix = "other").identity)
        assertNotEquals(s.identity, s.copy(upload = true).identity)
        val env = BazelRemoteSettings.credentialEnv(mapOf("S3_CACHE_ACCESS_KEY_ID" to "AK", "S3_CACHE_SECRET_ACCESS_KEY" to "SK"))
        assertEquals(mapOf("BAZEL_REMOTE_S3_ACCESS_KEY_ID" to "AK", "BAZEL_REMOTE_S3_SECRET_ACCESS_KEY" to "SK"), env)
        assertEquals("http://127.0.0.1:9090", s.url)
    }

    @Test fun `bazel-remote settings validation`() {
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "https://h", "bkt", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "h", "", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 0, 9090, "h", "bkt", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "h", "bkt", "../p", null, false) }
    }

    @Test fun `output groups and reserved build args`() {
        assertEquals(listOf("--output_groups=a,b"), BazelCommands.outputGroupFlags(listOf("a", "b")))
        assertEquals(emptyList<String>(), BazelCommands.outputGroupFlags(emptyList()))
        assertThrows<IllegalArgumentException> { BazelCommands.outputGroupFlags(listOf("a,b")) }
        assertThrows<IllegalArgumentException> { BazelCommands.validateArgs(emptyList(), listOf("--remote_cache=http://x")) }
        assertThrows<IllegalArgumentException> { BazelCommands.validateArgs(emptyList(), listOf("--noremote_upload_local_results")) }
        assertThrows<IllegalArgumentException> { BazelCommands.validateArgs(listOf("--output_user_root=/x"), emptyList()) }
        BazelCommands.validateArgs(listOf("--host_jvm_args=-Xmx1g"), listOf("-c", "opt"))
    }
}
