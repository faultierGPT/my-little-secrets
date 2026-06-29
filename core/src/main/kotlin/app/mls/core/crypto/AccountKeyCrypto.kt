package app.mls.core.crypto

/**
 * Generation and (un)wrapping of the random 32-byte account key — the REAL data key that
 * encrypts notes. Wrapping it (rather than encrypting notes directly under a password-derived
 * key) is what lets the password change, or a recovery key unlock, the same account key without
 * ever re-encrypting a single note.
 */
object AccountKeyCrypto {

    /** Fresh random 32-byte account key. */
    fun generate(): SecretBytes = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))

    /** wrappedAccountKey = XChaCha20Poly1305(accountKey, key=kek). */
    fun wrap(accountKey: SecretBytes, kek: SecretBytes): Sealed = seal(accountKey, kek, CryptoScheme.AAD_ACCOUNT_KEY)

    fun unwrap(wrapped: Sealed, kek: SecretBytes): SecretBytes = open(wrapped, kek, CryptoScheme.AAD_ACCOUNT_KEY)

    /** Second wrapping under the recovery-derived key. */
    fun wrapForRecovery(accountKey: SecretBytes, recoveryWrapKey: SecretBytes): Sealed =
        seal(accountKey, recoveryWrapKey, CryptoScheme.AAD_ACCOUNT_KEY_RECOVERY)

    fun unwrapFromRecovery(wrapped: Sealed, recoveryWrapKey: SecretBytes): SecretBytes =
        open(wrapped, recoveryWrapKey, CryptoScheme.AAD_ACCOUNT_KEY_RECOVERY)

    private fun seal(accountKey: SecretBytes, wrappingKey: SecretBytes, label: String): Sealed {
        val nonce = Sodium.randomBytes(Sodium.AEAD_NONCE_BYTES)
        val ct = Sodium.aeadEncrypt(accountKey.bytes(), aad(label), nonce, wrappingKey.bytes())
        return Sealed(ct, nonce)
    }

    private fun open(wrapped: Sealed, wrappingKey: SecretBytes, label: String): SecretBytes =
        SecretBytes.wrap(Sodium.aeadDecrypt(wrapped.ciphertext, aad(label), wrapped.nonce, wrappingKey.bytes()))

    private fun aad(label: String) = label.toByteArray(Charsets.US_ASCII)
}
