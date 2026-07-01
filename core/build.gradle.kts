plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

repositories {
    mavenCentral()
}

dependencies {
    // The crypto core exposes the libsodium binding through the `LazySodium` interface and
    // `com.sun.jna.NativeLong` (used in some signatures). These are intentionally `compileOnly`
    // so the JVM binding does NOT transitively leak to every consumer of `:core`:
    //   - JVM clients (desktop, server) bring in `lazysodium-java` + `jna` themselves.
    //   - Android brings in `lazysodium-android` (AAR) + `jna` (AAR).
    // Without this split the Android release classpath would carry BOTH `lazysodium-java` (jar)
    // and `lazysodium-android` (aar), and AGP's `checkReleaseDuplicateClasses` task would fail
    // with hundreds of duplicate `com.goterl.lazysodium.*` + `com.sun.jna.*` entries.
    //
    // `Sodium.<clinit>` is engineered to NEVER reference any concrete libsodium-flavored type at
    // class-init time. The `useBinding(...)` call installs a binding (JVM or Android) at process
    // start, after which the binding's class loader provides the types `pwhash(...)` needs
    // reflectively.
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
    // The JVM bindings are `compileOnly` above — they are NOT on `:core`'s runtime classpath,
    // but they ARE on the test classpath so the BeforeAll extension can construct a real
    // `LazySodiumJava` binding for `Sodium.useBinding(...)`.
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
