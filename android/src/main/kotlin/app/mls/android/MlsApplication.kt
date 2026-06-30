package app.mls.android

import android.app.Application
import app.mls.core.crypto.Sodium
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid

/**
 * Installs the Android libsodium binding into the shared crypto core BEFORE any cryptographic
 * operation runs. lazysodium-java's bundled natives don't cover Android ABIs, so the core's
 * [Sodium] uses whatever binding we provide here (lazysodium-android ships the arm/x86 `.so`).
 */
class MlsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Sodium.useBinding(LazySodiumAndroid(SodiumAndroid()))
    }
}
