import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    `java-gradle-plugin`
    // Version comes from the root project's classpath (alias(libs.plugins.kotlin.jvm)).
    id("org.jetbrains.kotlin.jvm")
}

group = "dev.pyrlyn"
version = "0.1.0-SNAPSHOT"

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
    }
}

gradlePlugin {
    plugins {
        create("gradleBazel") {
            id = "dev.pyrlyn.gradle-bazel"
            implementationClass = "dev.pyrlyn.gradlebazel.GradleBazelPlugin"
        }
    }
}
