rootProject.name = "my-little-secrets"

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

// Phase 1: shared crypto core. Phase 2: Ktor server. Later: :android, :desktop.
include(":core")
include(":server")
