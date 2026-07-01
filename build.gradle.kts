// Declare every plugin the build uses at the root with `apply false`, so the version catalog
// resolves them ONCE here and each subproject re-uses the same classloader entry. Without this,
// Gradle warns "The Kotlin Gradle plugin was loaded multiple times in different subprojects"
// because `alias(libs.plugins.kotlin.jvm)` in :core and `alias(libs.plugins.kotlin.compose)` in
// :android each pull the plugin in independently. The Android `application` plugin is also
// declared here because AGP needs a single consistent entrypoint across the build.
// See https://docs.gradle.org/current/userguide/plugins.html#sec:subprojects_plugins_dsl
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    group = "app.mls"
    version = "0.1.0"
}