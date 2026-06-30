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

// Phase 1: shared crypto core. Phase 2: Ktor server. Phase 4: shared design tokens.
// Phase 5: :desktop (pure Java + JavaFX). Later: :android.
include(":core")
include(":server")
include(":design")
include(":desktop")
