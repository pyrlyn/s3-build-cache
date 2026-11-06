import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    `maven-publish`
    // Version comes from the root project's classpath (alias(libs.plugins.kotlin.jvm)).
    id("org.jetbrains.kotlin.jvm")
}

group = "dev.pyrlyn"
version = "0.1.0"

repositories { mavenCentral() }

kotlin {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(gradleTestKit())
            }
        }
        // Real bazelisk + bazel-remote + MinIO (Testcontainers). Tools come from mise.toml; tests skip without them.
        register<JvmTestSuite>("e2eTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(gradleTestKit())
                implementation(platform(libs.awssdk.bom))
                implementation(libs.awssdk.s3)
                implementation(platform(libs.testcontainers.bom))
                implementation(libs.testcontainers.minio)
                implementation(libs.testcontainers.junit)
            }
            targets.all {
                testTask.configure {
                    val workspace = layout.projectDirectory.dir("src/e2eTest/workspace")
                    inputs.dir(workspace).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("e2eWorkspace")
                    systemProperty("e2e.workspace", workspace.asFile.absolutePath)
                    testLogging { events("passed", "skipped", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
                }
            }
        }
    }
}

gradlePlugin {
    testSourceSets(sourceSets["test"], sourceSets["e2eTest"])
    plugins {
        create("gradleBazel") {
            id = "dev.pyrlyn.gradle-bazel"
            implementationClass = "dev.pyrlyn.gradlebazel.GradleBazelPlugin"
            displayName = "Gradle -> Bazel bridge"
            description = "Runs explicit Bazel targets from Gradle and exports their outputs, with an optional S3-backed Bazel remote cache."
        }
    }
}
