package app.mls.android.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Optional fast unlock: seals the account key under a hardware-backed Android Keystore AES-GCM key
 * that REQUIRES a biometric to use ({@code setUserAuthenticationRequired}). The sealed blob lives in
 * app-private storage; the wrapping key is non-exportable and is invalidated if biometrics change
 * ({@code setInvalidatedByBiometricEnrollment}). This avoids re-deriving Argon2id on every unlock
 * WITHOUT ever persisting the account key in the clear — the key only exists in memory after a
 * successful biometric auth.
 */
class BiometricUnlock(context: Context) {

    private val appContext = context.applicationContext
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val blobFile = File(appContext.filesDir, "biometric.blob")

    /** True if the device has enrolled strong biometrics we can use. */
    fun isBiometricAvailable(): Boolean =
        BiometricManager.from(appContext)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** True if a sealed account key is present (biometric unlock was set up). */
    fun isConfigured(): Boolean = blobFile.isFile && keyStore.containsAlias(KEY_ALIAS)

    /** Seal [accountKey] under a fresh biometric-bound key. [accountKey] is the caller's to wipe. */
    suspend fun enable(activity: FragmentActivity, accountKey: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        val authed = authenticate(activity, cipher, "Enable biometric unlock")
        val ciphertext = authed.doFinal(accountKey)
        try {
            writeBlob(authed.iv, ciphertext)
        } finally {
            Arrays.fill(ciphertext, 0)
        }
    }

    /** Recover the account key after a biometric auth. Returns its bytes; the caller wipes them. */
    suspend fun unlock(activity: FragmentActivity): ByteArray {
        require(isConfigured()) { "biometric unlock is not configured" }
        val (iv, ciphertext) = readBlob()
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        val authed = authenticate(activity, cipher, "Unlock my-little-secrets")
        return authed.doFinal(ciphertext)
    }

    /** Forget any sealed key (e.g. on sign-out). */
    fun clear() {
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
        blobFile.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        return generator.generateKey()
    }

    private suspend fun authenticate(activity: FragmentActivity, cipher: Cipher, title: String): Cipher =
        suspendCancellableCoroutine { cont ->
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authedCipher = result.cryptoObject?.cipher
                        if (authedCipher != null) {
                            cont.resume(authedCipher)
                        } else {
                            cont.resumeWithException(VaultException("Biometric cipher unavailable."))
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        cont.resumeWithException(VaultException(errString.toString()))
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Use your fingerprint or face to continue")
                .setNegativeButtonText("Use password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        }

    private fun writeBlob(iv: ByteArray, ciphertext: ByteArray) {
        blobFile.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(ciphertext)
        }
    }

    private fun readBlob(): Pair<ByteArray, ByteArray> {
        blobFile.inputStream().use { input ->
            val ivLen = input.read()
            val iv = ByteArray(ivLen)
            var read = 0
            while (read < ivLen) read += input.read(iv, read, ivLen - read)
            val ciphertext = input.readBytes()
            return iv to ciphertext
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "mls.biometric.unlock"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
