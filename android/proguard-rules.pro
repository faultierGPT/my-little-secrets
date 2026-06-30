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
