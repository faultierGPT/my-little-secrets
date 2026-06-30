plugins {
    application
    // Resolves the platform-correct JavaFX native artifacts (Phase 6 builds per-OS releases).
    id("org.openjfx.javafxplugin") version "0.1.0"
}

repositories {
    mavenCentral()
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

javafx {
    version = "21.0.6"
    modules = listOf("javafx.controls", "javafx.graphics")
}

dependencies {
    // The vetted crypto + sync + API core. The desktop app reaches the suspending API only through
    // core's app.mls.core.jvm blocking adapters, so this module stays pure Java.
    implementation(project(":core"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)

    // End-to-end integration: run the REAL server in-process over embedded H2, and drive it through
    // the desktop's own controller — no network, no Docker, no mocks of our own code.
    testImplementation(project(":server"))
    testImplementation(libs.h2)

    // Headless JavaFX (Monocle) for a best-effort UI bring-up smoke test.
    testImplementation("org.testfx:openjfx-monocle:21.0.2")
}

sourceSets {
    main {
        // Consume the GENERATED design constants (MlsTokens.java) — single source of truth in :design.
        java { srcDir(layout.projectDirectory.dir("../design/generated/java")) }
        // The generated JavaFX stylesheet ships on the classpath as /mls-theme.css.
        resources { srcDir(layout.projectDirectory.dir("../design/generated/javafx")) }
    }
}

application {
    mainClass = "app.mls.desktop.DesktopApp"
}

tasks.test {
    useJUnitPlatform()
    // Argon2id at the default 256 MiB profile allocates native memory; give headroom.
    maxHeapSize = "1g"
    // Run JavaFX tests under headless Monocle + software pipeline (no X11/GL in CI/sandbox).
    systemProperty("testfx.headless", "true")
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    // On-demand snapshot rendering (SnapshotHarness): forward a font directory + output dir from the
    // invoking JVM when present, so a bare sandbox with no system fonts can still render real PNGs.
    listOf("mls.snapshot.dir", "prism.fontdir", "prism.embeddedfonts", "prism.verbose", "javafx.verbose").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
    System.getProperty("mls.ldlibrarypath")?.let { environment("LD_LIBRARY_PATH", it) }
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
