import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.pyrlyn"
version = "0.1.0"

repositories { mavenCentral() }

kotlin {
    compilerOptions {
        // Keep the plugin loadable by Gradle 9.x's embedded Kotlin (9.0 ships Kotlin 2.2).
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

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
        named<JvmTestSuite>("test") {
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
