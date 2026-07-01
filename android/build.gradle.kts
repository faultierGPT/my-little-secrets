// NOTE: settings.gradle.kts includes this module only when an Android SDK is configured or an
// Android task is requested explicitly. That keeps SDK-less JVM builds working while still allowing
// direct commands such as `./gradlew :android:assembleRelease`.
// See android/README.md.

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 provides built-in Kotlin support. Do not apply org.jetbrains.kotlin.android here.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.mls.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.mls.android"
        minSdk = 26          // Keystore AES-GCM + setUserAuthenticationRequired biometric binding
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Consume the GENERATED Compose theme tokens (single source of truth in :design).
    sourceSets["main"].kotlin.directories.add("../design/generated/compose")

    // Release signing comes ENTIRELY from the environment; no keystore or password is ever in the
    // repo (see RELEASE.md / scripts/gen-signing-keys.sh). When the env is unset (e.g. a plain CI
    // build check), the release APK is left unsigned rather than failing the build.
    val releaseKeystore = System.getenv("MLS_ANDROID_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("MLS_ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MLS_ANDROID_KEY_ALIAS") ?: "mls-release"
                keyPassword = System.getenv("MLS_ANDROID_KEY_PASSWORD")
                    ?: System.getenv("MLS_ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// `:core` declares `lazysodium-java` + `jna` as `compileOnly` (no JVM jars reach Android's
// classpath) and the Android build pulls in `lazysodium-android@aar` + `jna@aar` directly below.
// No `configurations.all { exclude(...) }` block is needed here: Gradle's variant resolution
// gives Android the AAR variants and never pulls in JVM-jar equivalents for a `compileOnly`
// dependency that has no concrete Java-coordinates variant at the consumer.

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // The vetted crypto + sync + API core. Android reuses it directly: its suspend API is a natural
    // fit for coroutines (no blocking bridge needed, unlike the Java desktop client).
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    // Android needs lazysodium's ANDROID native bundle; installed into core's Sodium at startup.
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // Use OkHttp as the Ktor engine on Android (CIO works too; OkHttp integrates with the platform).
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
}
