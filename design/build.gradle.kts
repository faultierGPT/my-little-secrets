plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // The design layer is pure data + string rendering. Its ONLY dependency is JSON
    // serialization, used to emit the toolkit-neutral tokens.json export. It deliberately
    // does NOT depend on Compose or JavaFX — it generates source/CSS for them as text, so
    // it stays buildable on any JVM and is shared by both clients without coupling.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(21)
}

// `./gradlew :design:run` regenerates every rendered artifact from the canonical tokens.
application {
    mainClass.set("app.mls.design.GenerateKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
