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
        val env = BazelRemoteSettings.credentialEnv("access_key", mapOf("S3_CACHE_ACCESS_KEY_ID" to "AK", "S3_CACHE_SECRET_ACCESS_KEY" to "SK"))
        assertEquals(mapOf("BAZEL_REMOTE_S3_ACCESS_KEY_ID" to "AK", "BAZEL_REMOTE_S3_SECRET_ACCESS_KEY" to "SK"), env)
        assertEquals("http://127.0.0.1:9090", s.url)
    }

    @Test fun `bazel-remote settings validation`() {
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "https://h", "bkt", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "h", "", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 0, 9090, "h", "bkt", "p", null, false) }
        assertThrows<IllegalArgumentException> { BazelRemoteSettings("b", "/c", 5, 9090, "h", "bkt", "../p", null, false) }
    }

    @Test fun `bazel-remote auth methods`() {
        val base = BazelRemoteSettings("b", "/c", 5, 9090, "h", "bkt", "p", null, false)
        val iam = base.copy(authMethod = "iam_role", iamRoleEndpoint = "http://169.254.169.254").command()
        assertTrue("--s3.auth_method=iam_role" in iam && "--s3.iam_role_endpoint=http://169.254.169.254" in iam, iam.toString())
        val file = base.copy(authMethod = "aws_credentials_file", credentialsFile = "/etc/creds", awsProfile = "ci").command()
        assertTrue("--s3.auth_method=aws_credentials_file" in file, file.toString())
        assertTrue("--s3.aws_shared_credentials_file=/etc/creds" in file && "--s3.aws_profile=ci" in file, file.toString())
        assertEquals(base.identity, base.copy(authMethod = "iam_role").identity, "auth does not change cache identity")
        assertThrows<IllegalArgumentException> { base.copy(authMethod = "anonymous") }
        assertThrows<IllegalArgumentException> { base.copy(iamRoleEndpoint = "http://x") }
        assertThrows<IllegalArgumentException> { base.copy(authMethod = "iam_role", credentialsFile = "/x") }

        val keys = mapOf("S3_CACHE_ACCESS_KEY_ID" to "AK", "S3_CACHE_SECRET_ACCESS_KEY" to "SK")
        assertEquals(emptyMap<String, String>(), BazelRemoteSettings.credentialEnv("iam_role", keys), "keys only for access_key")
        val e = assertThrows<IllegalArgumentException> {
            BazelRemoteSettings.credentialEnv("access_key", mapOf("S3_CACHE_ACCESS_KEY_ID" to "AK"))
        }
        assertTrue(e.message!!.contains("S3_CACHE_SECRET_ACCESS_KEY") && !e.message!!.contains("AK,"), e.message)
    }

    @Test fun `bazel info keys are requested and parsed`() {
        assertEquals(listOf("bazel", "info", "-c", "opt", "execution_root", "output_base", "install_base"),
            BazelCommands.info("bazel", emptyList(), listOf("-c", "opt")))
        val info = BazelCommands.parseInfo("execution_root: /o/execroot/_main\noutput_base: /o\ninstall_base: /r/install/x\n")
        assertEquals("/o/execroot/_main", info["execution_root"])
        assertThrows<IllegalArgumentException> { BazelCommands.parseInfo("/o/execroot/_main\n") }
        assertThrows<IllegalArgumentException> { BazelCommands.parseInfo("execution_root: /o\n") }
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
