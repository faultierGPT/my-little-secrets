plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    // The crypto core depends ONLY on libsodium (via lazysodium) + serialization.
    // No bespoke crypto, ever.
    implementation(libs.lazysodium.java)
    implementation(libs.jna) // lazysodium uses JNA NativeLong in its public API
    implementation(libs.kotlinx.serialization.json)

    // Networking + sync (Phase 3). Engine is injectable; CIO is the default JVM/Android engine.
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.cio)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
    // Argon2id at the real 256 MiB profile allocates native memory; give headroom.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
