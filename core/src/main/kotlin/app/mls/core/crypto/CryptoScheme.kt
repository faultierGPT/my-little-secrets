package app.mls.core.crypto

/**
 * Versioned constants for the crypto scheme. Every encrypted record stores [SCHEME_VERSION]
 * so the scheme can evolve (new KDF params, rotated labels, new AEAD) without breaking old
 * data: decryption dispatches on the stored version.
 */
object CryptoScheme {
    /** Bumped when the overall crypto construction changes. Stored on every sealed record. */
    const val SCHEME_VERSION = 1

    /** Bumped when the note plaintext JSON shape changes. Stored INSIDE the encrypted payload. */
    const val NOTE_SCHEMA_VERSION = 1

    // crypto_kdf contexts — MUST be exactly 8 ASCII bytes (KeyDerivation.CONTEXT_BYTES).
    const val KDF_CONTEXT_MASTER = "mlskdf01"   // subkeys derived from masterKey
    const val KDF_CONTEXT_RECOVERY = "mlsrcv01"  // wrapping key derived from the recovery secret

    // Subkey ids under KDF_CONTEXT_MASTER (domain separation: authKey ⟂ keyEncryptionKey).
    const val SUBKEY_AUTH = 1L
    const val SUBKEY_KEK = 2L
    // Subkey id under KDF_CONTEXT_RECOVERY.
    const val SUBKEY_RECOVERY_WRAP = 1L

    // AEAD associated-data domain labels. Binding these as AAD ties each ciphertext to its
    // purpose (and a note to its id), so the server cannot silently swap one blob for another.
    const val AAD_ACCOUNT_KEY = "mls.accountKey.v1"
    const val AAD_ACCOUNT_KEY_RECOVERY = "mls.accountKey.recovery.v1"
    const val AAD_NOTE_PREFIX = "mls.note.v1"
}
