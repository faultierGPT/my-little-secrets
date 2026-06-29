package app.mls.server.auth

import app.mls.core.crypto.Sodium
import java.security.MessageDigest
import java.util.Base64

/**
 * Opaque session tokens. The raw token (32 CSPRNG bytes, URL-safe Base64) goes to the client;
 * only its SHA-256 hash is stored server-side, so a DB leak does not hand over live sessions.
 * (SHA-256 of a 256-bit high-entropy token needs no salt/stretching — there is nothing to
 * brute-force.)
 */
object Tokens {
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun newRawToken(): String = urlEncoder.encodeToString(Sodium.randomBytes(32))

    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
