// Fail fast on old JDKs; the build otherwise uses whatever JDK runs Gradle (no toolchain pinning).
require(JavaVersion.current() >= JavaVersion.VERSION_21) {
    "s3-build-cache requires JDK 21 or newer, but Gradle is running on ${JavaVersion.current()} (${System.getProperty("java.home")})"
}

rootProject.name = "s3-build-cache"
