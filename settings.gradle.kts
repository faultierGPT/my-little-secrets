rootProject.name = "my-little-secrets"

fun androidSdkPath(): String? {
    val localProperties = file("local.properties")
    if (localProperties.isFile) {
        val properties = java.util.Properties()
        localProperties.inputStream().use(properties::load)
        properties.getProperty("sdk.dir")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return System.getenv("ANDROID_HOME")?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv("ANDROID_SDK_ROOT")?.trim()?.takeIf { it.isNotEmpty() }
}

val androidTaskRequested = gradle.startParameter.taskNames.any { taskName ->
    taskName == ":android" || taskName.startsWith(":android:") ||
        taskName == "android" || taskName.startsWith("android:")
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

// Phase 1: shared crypto core. Phase 2: Ktor server. Phase 4: shared design tokens.
// Phase 5: :desktop (pure Java + JavaFX). Android is included only when an SDK exists, or when
// an Android task was requested explicitly, so SDK-less JVM builds keep working.
include(":core")
include(":server")
include(":design")

// :desktop is included when its source tree is on disk. In the server's Docker build the
// `desktop/` directory is excluded by `.dockerignore` (it's a JavaFX client, irrelevant to a
// server image and would bloat the build context). Skipping the include keeps `server:installDist`
// from failing on a missing :desktop project directory in that environment.
if (file("desktop").isDirectory) {
    include(":desktop")
}

if (androidSdkPath() != null || androidTaskRequested) {
    include(":android")
}
