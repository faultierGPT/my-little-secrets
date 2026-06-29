package app.mls.core.crypto

import app.mls.core.model.EncryptedBlob

/**
 * A single AEAD output: ciphertext (incl. the 16-byte Poly1305 tag) + the 24-byte nonce that
 * produced it, tagged with the [schemeVersion] used. The nonce is NOT secret and travels with
 * the ciphertext. Maps to/from the wire form [EncryptedBlob] (base64).
 */
class Sealed(
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val schemeVersion: Int = CryptoScheme.SCHEME_VERSION,
) {
    fun toBlob(): EncryptedBlob = EncryptedBlob(
        ciphertext = B64.encode(ciphertext),
        nonce = B64.encode(nonce),
        schemeVersion = schemeVersion,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Sealed) return false
        return schemeVersion == other.schemeVersion &&
            ciphertext.contentEquals(other.ciphertext) &&
            nonce.contentEquals(other.nonce)
    }

    override fun hashCode(): Int =
        (ciphertext.contentHashCode() * 31 + nonce.contentHashCode()) * 31 + schemeVersion

    companion object {
        fun fromBlob(b: EncryptedBlob): Sealed =
            Sealed(B64.decode(b.ciphertext), B64.decode(b.nonce), b.schemeVersion)
    }
}
