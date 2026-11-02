plugins {
    `kotlin-dsl`
}

group = "dev.pyrlyn"
version = "0.1.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

dependencies {
    implementation(platform(libs.awssdk.bom))
    implementation(libs.awssdk.s3) {
        // apache-client (pooled) is used; netty is only needed by the async client.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awssdk.apache)
}

testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.mockk)
            }
        }
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(project())
                implementation(gradleApi())
                implementation(platform(libs.awssdk.bom))
                implementation(libs.awssdk.s3)
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.minio)
                implementation(libs.testcontainers.junit)
            }
            targets.all {
                testTask.configure {
                    // Colima exposes Docker on a non-default socket; point Testcontainers at it if present.
                    val colima = File(System.getProperty("user.home"), ".colima/default/docker.sock")
                    if (System.getenv("DOCKER_HOST") == null && colima.exists()) {
                        environment("DOCKER_HOST", "unix://${colima.absolutePath}")
                        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
                    }
                    shouldRunAfter(tasks.named("test"))
                }
            }
        }
    }
}

gradlePlugin {
    plugins {
        create("s3BuildCache") {
            id = "dev.pyrlyn.s3-build-cache"
            implementationClass = "dev.pyrlyn.s3buildcache.S3BuildCachePlugin"
        }
    }
}
