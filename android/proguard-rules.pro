# lazysodium + JNA reach native code by reflection; keep their public surface.
-keep class com.goterl.lazysodium.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# kotlinx.serialization generated serializers used by the core DTOs.
-keepclassmembers class app.mls.core.model.** { *; }
-keepclassmembers class app.mls.core.crypto.KdfParams { *; }
-keep,includedescriptorclasses class app.mls.core.**$$serializer { *; }
-keepclasseswithmembers class app.mls.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor / OkHttp engine.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**

# `:core`'s `Sodium` no longer carries a JVM-default binding (`compileOnly` keeps the JVM jars
# off the Android classpath, and `Sodium.<clinit>` references no concrete libsodium type).
# Android's binding is installed at process start by `MlsApplication.onCreate()` via
# `Sodium.useBinding(LazySodiumAndroid(SodiumAndroid()))` BEFORE any cryptographic call,
# using the AAR-flavored `lazysodium-android` classes. R8's standard reachability checks
# see only the Android interface and never produce a `-dontwarn` for `LazySodiumJava`/
# `SodiumJava`.
#
# JNA carries desktop-only AWT helpers that R8 should ignore on Android.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
