plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    // The crypto core exposes the libsodium binding through its public API (Sodium.kt holds a
    // `LazySodium` instance and uses `com.sun.jna.NativeLong` in some signatures). These are
    // intentionally `compileOnly` so the platform-specific binding does NOT leak transitively to
    // every consumer of :core:
    //   - JVM clients (desktop, server) bring in `lazysodium-java` + `jna` themselves.
    //   - Android brings in `lazysodium-android` (AAR) + `jna` (AAR).
    // Without this split the Android release classpath would carry BOTH `lazysodium-java` (jar)
    // and `lazysodium-android` (aar), and AGP's `checkReleaseDuplicateClasses` task would fail
    // with hundreds of duplicate `com.goterl.lazysodium.*` + `com.sun.jna.*` entries.
    compileOnly(libs.lazysodium.java)
    compileOnly(libs.jna) // lazysodium uses JNA NativeLong in its public API
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
    // The default `Sodium` binding is `LazySodiumJava(SodiumJava())`. Tests run on the JVM, so
    // bring the JVM binding in at test scope. (Production JVM apps — desktop, server — also pull
    // it in as `implementation`; Android replaces the binding via `Sodium.useBinding(...)`.)
    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
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
