package app.mls.core.crypto

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import com.goterl.lazysodium.interfaces.AEAD
import com.goterl.lazysodium.interfaces.KeyDerivation
import com.goterl.lazysodium.interfaces.PwHash
import com.sun.jna.NativeLong

/**
 * The single boundary to libsodium (via lazysodium-java). EVERY cryptographic operation in
 * this module flows through here, so the "vetted library only — nothing hand-rolled"
 * invariant is auditable in exactly one file.
 *
 * Primitives used (all libsodium):
 *  - Argon2id        : crypto_pwhash / crypto_pwhash_str            (KDF + server-side verifier)
 *  - XChaCha20-Poly1305 IETF : crypto_aead_xchacha20poly1305_ietf_* (authenticated encryption)
 *  - crypto_kdf (BLAKE2b)    : crypto_kdf_derive_from_key            (domain-separated subkeys)
 *  - randombytes_buf         : CSPRNG
 */
object Sodium {

    /** Process-wide libsodium handle. lazysodium loads the bundled native library on first use. */
    val ls: LazySodiumJava = LazySodiumJava(SodiumJava())

    // ---- Sizes (libsodium-standard constants, surfaced via the binding) ----
    val AEAD_KEY_BYTES: Int = AEAD.XCHACHA20POLY1305_IETF_KEYBYTES       // 32
    val AEAD_NONCE_BYTES: Int = AEAD.XCHACHA20POLY1305_IETF_NPUBBYTES    // 24
    val AEAD_TAG_BYTES: Int = AEAD.XCHACHA20POLY1305_IETF_ABYTES         // 16
    val KDF_KEY_BYTES: Int = KeyDerivation.MASTER_KEY_BYTES             // 32
    val KDF_CONTEXT_BYTES: Int = KeyDerivation.CONTEXT_BYTES             // 8
    val PWHASH_SALT_BYTES: Int = PwHash.SALTBYTES                        // 16
    val PWHASH_STR_BYTES: Int = PwHash.STR_BYTES                         // 128

    // ---- CSPRNG ----
    fun randomBytes(n: Int): ByteArray = ls.randomBytesBuf(n)

    /** Best-effort native zeroization of an array we own. */
    fun memzero(b: ByteArray) {
        ls.sodiumMemZero(b, b.size)
    }

    // ---- Argon2id (crypto_pwhash) ----
    /** Derive [outLen] bytes of key material from [password] + [salt] with the given Argon2id cost. */
    fun pwhash(outLen: Int, password: ByteArray, salt: ByteArray, ops: Long, memBytes: Long): ByteArray {
        require(salt.size == PWHASH_SALT_BYTES) { "salt must be $PWHASH_SALT_BYTES bytes, got ${salt.size}" }
        val out = ByteArray(outLen)
        val ok = ls.cryptoPwHash(
            out, out.size, password, password.size, salt, ops, NativeLong(memBytes),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13,
        )
        check(ok) { "crypto_pwhash failed (invalid params or out of memory)" }
        return out
    }

    /** Produce a self-describing PHC verifier string `$argon2id$...` over [password]. */
    fun pwhashStr(password: ByteArray, ops: Long, memBytes: Long): String {
        val out = ByteArray(PWHASH_STR_BYTES)
        val ok = ls.cryptoPwHashStr(out, password, password.size, ops, NativeLong(memBytes))
        check(ok) { "crypto_pwhash_str failed" }
        return out.decodeToString().substringBefore('\u0000')
    }

    /** Constant-time verification of [password] against a stored PHC string. */
    fun pwhashStrVerify(phc: String, password: ByteArray): Boolean {
        val buf = ByteArray(PWHASH_STR_BYTES)
        val bytes = phc.toByteArray(Charsets.US_ASCII)
        require(bytes.size < PWHASH_STR_BYTES) { "verifier string too long" }
        bytes.copyInto(buf)
        return ls.cryptoPwHashStrVerify(buf, password, password.size)
    }

    // ---- crypto_kdf subkey derivation (BLAKE2b) ----
    /** Derive an independent subkey from a 32-byte high-entropy [masterKey]. */
    fun kdfDerive(subkeyLen: Int, subkeyId: Long, context: String, masterKey: ByteArray): ByteArray {
        require(masterKey.size == KDF_KEY_BYTES) { "kdf master key must be $KDF_KEY_BYTES bytes" }
        val ctx = context.toByteArray(Charsets.US_ASCII)
        require(ctx.size == KDF_CONTEXT_BYTES) { "kdf context must be exactly $KDF_CONTEXT_BYTES ASCII bytes, got ${ctx.size}" }
        val sub = ByteArray(subkeyLen)
        val rc = ls.cryptoKdfDeriveFromKey(sub, sub.size, subkeyId, ctx, masterKey)
        check(rc == 0) { "crypto_kdf_derive_from_key failed (rc=$rc)" }
        return sub
    }

    // ---- XChaCha20-Poly1305 IETF (AEAD) ----
    /** Encrypt [message] under [key] with [nonce] (24 random bytes) and associated data [aad]. */
    fun aeadEncrypt(message: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == AEAD_NONCE_BYTES) { "nonce must be $AEAD_NONCE_BYTES bytes" }
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        val c = ByteArray(message.size + AEAD_TAG_BYTES)
        val cLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            c, cLen, message, message.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
        )
        check(ok) { "AEAD encryption failed" }
        return if (cLen[0].toInt() == c.size) c else c.copyOf(cLen[0].toInt())
    }

    /**
     * Decrypt and verify. Throws [AeadAuthException] on ANY authentication failure — a wrong key,
     * a flipped ciphertext bit, a wrong nonce, or mismatched associated data all land here.
     */
    fun aeadDecrypt(ciphertext: ByteArray, aad: ByteArray, nonce: ByteArray, key: ByteArray): ByteArray {
        require(nonce.size == AEAD_NONCE_BYTES) { "nonce must be $AEAD_NONCE_BYTES bytes" }
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        require(ciphertext.size >= AEAD_TAG_BYTES) { "ciphertext shorter than the $AEAD_TAG_BYTES-byte auth tag" }
        val m = ByteArray(ciphertext.size - AEAD_TAG_BYTES)
        val mLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            m, mLen, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key,
        )
        if (!ok) {
            java.util.Arrays.fill(m, 0)
            throw AeadAuthException()
        }
        return if (mLen[0].toInt() == m.size) m else m.copyOf(mLen[0].toInt())
    }
}

/** Thrown when AEAD decryption fails its Poly1305 authentication check. */
class AeadAuthException : RuntimeException(
    "AEAD authentication failed: wrong key, tampered ciphertext, wrong nonce, or wrong associated data",
)
