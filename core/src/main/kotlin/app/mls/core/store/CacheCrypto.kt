package app.mls.core.store

import app.mls.core.crypto.SecretBytes
import app.mls.core.crypto.Sodium

/**
 * Encrypts the local cache at rest with a key DERIVED from the account key (so the cache is bound
 * to the unlocked account and needs no separate secret). Same AEAD as everything else:
 * XChaCha20-Poly1305 with a fresh random nonce prepended to the ciphertext.
 */
object CacheCrypto {
    private const val CONTEXT = "mlscach1"  // 8 bytes (crypto_kdf context)
    private const val SUBKEY_ID = 1L
    private val AAD = "mls.cache.v1".toByteArray(Charsets.US_ASCII)

    fun deriveCacheKey(accountKey: SecretBytes): SecretBytes =
        SecretBytes.wrap(Sodium.kdfDerive(Sodium.AEAD_KEY_BYTES, SUBKEY_ID, CONTEXT, accountKey.bytes()))

    /** nonce(24) || ciphertext(+tag). */
    fun seal(plaintext: ByteArray, cacheKey: SecretBytes): ByteArray {
        val nonce = Sodium.randomBytes(Sodium.AEAD_NONCE_BYTES)
        val ct = Sodium.aeadEncrypt(plaintext, AAD, nonce, cacheKey.bytes())
        return nonce + ct
    }

    fun open(blob: ByteArray, cacheKey: SecretBytes): ByteArray {
        require(blob.size > Sodium.AEAD_NONCE_BYTES) { "cache blob too short" }
        val nonce = blob.copyOfRange(0, Sodium.AEAD_NONCE_BYTES)
        val ct = blob.copyOfRange(Sodium.AEAD_NONCE_BYTES, blob.size)
        return Sodium.aeadDecrypt(ct, AAD, nonce, cacheKey.bytes())
    }
}
