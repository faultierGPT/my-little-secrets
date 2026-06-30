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

# :core's `Sodium` object defaults its binding to `LazySodiumJava(SodiumJava())` on the JVM.
# On Android the binding is replaced by `MlsApplication.onCreate()` via `Sodium.useBinding(...)`
# BEFORE any cryptographic call, so the JVM-default classes are never loaded at runtime.
# R8 still sees the static `<clinit>` reference and would otherwise error out at minify time.
# We only suppress the warning — `-keep` is not appropriate because the classes are absent
# on the Android runtime classpath (lazysodium-android ships its own bindings).
-dontwarn com.goterl.lazysodium.LazySodiumJava
-dontwarn com.goterl.lazysodium.SodiumJava
# JNA has desktop-only AWT helpers that R8 should ignore on Android.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
