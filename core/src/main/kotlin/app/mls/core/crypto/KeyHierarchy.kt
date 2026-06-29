package app.mls.core.crypto

/**
 * Derivation of every key from the master password. Pure functions, no I/O, no network.
 *
 * ```
 * masterKey        = Argon2id(password, salt, kdfParams)        (never leaves device)
 * authKey          = crypto_kdf(masterKey, ctx="mlskdf01", id=1) (credential proof to server)
 * keyEncryptionKey = crypto_kdf(masterKey, ctx="mlskdf01", id=2) (never leaves device)
 * recoveryWrapKey  = crypto_kdf(recoveryKey, ctx="mlsrcv01", id=1)
 * ```
 */
object KeyHierarchy {

    /** masterKey = Argon2id(password, salt, params). 32 bytes. Never leaves the device. */
    fun deriveMasterKey(password: ByteArray, salt: ByteArray, params: KdfParams): SecretBytes =
        SecretBytes.wrap(Sodium.pwhash(Sodium.KDF_KEY_BYTES, password, salt, params.opsLimit, params.memLimitBytes))

    /** authKey — the credential proof sent to the server (which stores only Argon2id(authKey)). */
    fun deriveAuthKey(masterKey: SecretBytes): SecretBytes =
        SecretBytes.wrap(
            Sodium.kdfDerive(Sodium.AEAD_KEY_BYTES, CryptoScheme.SUBKEY_AUTH, CryptoScheme.KDF_CONTEXT_MASTER, masterKey.bytes()),
        )

    /** keyEncryptionKey (KEK) — wraps/unwraps the account key. Never leaves the device. */
    fun deriveKek(masterKey: SecretBytes): SecretBytes =
        SecretBytes.wrap(
            Sodium.kdfDerive(Sodium.AEAD_KEY_BYTES, CryptoScheme.SUBKEY_KEK, CryptoScheme.KDF_CONTEXT_MASTER, masterKey.bytes()),
        )

    /** Wrapping key derived from a recovery code's 32 random bytes. */
    fun deriveRecoveryWrapKey(recoveryKey: ByteArray): SecretBytes =
        SecretBytes.wrap(
            Sodium.kdfDerive(Sodium.AEAD_KEY_BYTES, CryptoScheme.SUBKEY_RECOVERY_WRAP, CryptoScheme.KDF_CONTEXT_RECOVERY, recoveryKey),
        )
}
