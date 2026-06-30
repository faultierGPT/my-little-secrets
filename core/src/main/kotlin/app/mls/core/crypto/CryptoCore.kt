package app.mls.core.crypto

/**
 * Material produced by local registration: what to send the server + what to show the user.
 *
 * Ownership: [authKey] and [recoveryCode] are wipeable secrets the CALLER must [SecretBytes.close]
 * after they are consumed (authKey once the register request is sent; recoveryCode once shown to the
 * user). [accountKey] is the long-lived unlocked key for the session and is owned by the session,
 * not closed here. Closing the material would kill the session key, so it is deliberately not
 * AutoCloseable.
 */
class RegistrationMaterial(
    val salt: ByteArray,
    val kdfParams: KdfParams,
    val authKey: SecretBytes,               // sent to server; server stores Argon2id(authKey). Caller wipes after upload.
    val wrappedAccountKey: Sealed,
    val wrappedAccountKeyRecovery: Sealed?, // present iff recovery enabled
    val recoveryCode: RecoveryCode?,        // shown ONCE to the user, never persisted. Caller wipes after display.
    val accountKey: SecretBytes,            // unlocked, held in memory only (session-owned)
)

/** Result of re-wrapping the same account key for a new password. [authKey] is caller-wiped after upload. */
class RewrapResult(
    val salt: ByteArray,
    val kdfParams: KdfParams,
    val authKey: SecretBytes,
    val wrappedAccountKey: Sealed,
)

/**
 * High-level client crypto operations: register, derive-login-credential, unlock, rewrap.
 * This is the surface the apps call; everything beneath it is small, single-purpose, tested.
 * Intermediate key material (masterKey, KEK, authKey, recovery wrap key) is wiped in `finally`
 * blocks the instant it is no longer needed.
 */
object CryptoCore {

    /** Local signup: derive keys, generate + wrap a random account key, optionally a recovery wrap. */
    fun register(
        password: ByteArray,
        params: KdfParams = KdfParams.DEFAULT,
        withRecovery: Boolean = true,
    ): RegistrationMaterial {
        val salt = Sodium.randomBytes(Sodium.PWHASH_SALT_BYTES)
        val master = KeyHierarchy.deriveMasterKey(password, salt, params)
        try {
            val authKeySecret = KeyHierarchy.deriveAuthKey(master)
            val kek = KeyHierarchy.deriveKek(master)
            try {
                val accountKey = AccountKeyCrypto.generate()
                val wrapped = AccountKeyCrypto.wrap(accountKey, kek)

                var recoveryCode: RecoveryCode? = null
                var wrappedRecovery: Sealed? = null
                if (withRecovery) {
                    val rc = RecoveryCode.generate()
                    val rwk = KeyHierarchy.deriveRecoveryWrapKey(rc.rawKey())
                    try {
                        wrappedRecovery = AccountKeyCrypto.wrapForRecovery(accountKey, rwk)
                    } finally {
                        rwk.destroy()
                    }
                    recoveryCode = rc // returned live; the caller wipes it after showing the user
                }

                return RegistrationMaterial(
                    salt = salt,
                    kdfParams = params,
                    authKey = SecretBytes.wrap(authKeySecret.copyBytes()), // caller-owned, wipeable copy of the credential
                    wrappedAccountKey = wrapped,
                    wrappedAccountKeyRecovery = wrappedRecovery,
                    recoveryCode = recoveryCode,
                    accountKey = accountKey,
                )
            } finally {
                authKeySecret.destroy()
                kek.destroy()
            }
        } finally {
            master.destroy()
        }
    }

    /** Login step 2: derive the authKey to present, given the server-provided salt + params. */
    fun deriveAuthKeyForLogin(password: ByteArray, salt: ByteArray, params: KdfParams): SecretBytes {
        val master = KeyHierarchy.deriveMasterKey(password, salt, params)
        try {
            return KeyHierarchy.deriveAuthKey(master)
        } finally {
            master.destroy()
        }
    }

    /** Unlock: turn password + the wrapped key into the in-memory account key. */
    fun unlockWithPassword(
        password: ByteArray,
        salt: ByteArray,
        params: KdfParams,
        wrappedAccountKey: Sealed,
    ): SecretBytes {
        val master = KeyHierarchy.deriveMasterKey(password, salt, params)
        try {
            val kek = KeyHierarchy.deriveKek(master)
            try {
                return AccountKeyCrypto.unwrap(wrappedAccountKey, kek)
            } finally {
                kek.destroy()
            }
        } finally {
            master.destroy()
        }
    }

    /** Unlock via the recovery code instead of the password. */
    fun unlockWithRecovery(recovery: RecoveryCode, wrappedAccountKeyRecovery: Sealed): SecretBytes {
        val rwk = KeyHierarchy.deriveRecoveryWrapKey(recovery.rawKey())
        try {
            return AccountKeyCrypto.unwrapFromRecovery(wrappedAccountKeyRecovery, rwk)
        } finally {
            rwk.destroy()
        }
    }

    /**
     * Password change: re-wrap the SAME [accountKey] under a key derived from [newPassword].
     * Notes are never re-encrypted (their key, the account key, is unchanged). The caller then
     * uploads the new salt/params/authKey/wrappedAccountKey and the server invalidates old sessions.
     */
    fun rewrapForNewPassword(
        accountKey: SecretBytes,
        newPassword: ByteArray,
        params: KdfParams = KdfParams.DEFAULT,
    ): RewrapResult {
        val salt = Sodium.randomBytes(Sodium.PWHASH_SALT_BYTES)
        val master = KeyHierarchy.deriveMasterKey(newPassword, salt, params)
        try {
            val authKeySecret = KeyHierarchy.deriveAuthKey(master)
            val kek = KeyHierarchy.deriveKek(master)
            try {
                val wrapped = AccountKeyCrypto.wrap(accountKey, kek)
                return RewrapResult(salt, params, SecretBytes.wrap(authKeySecret.copyBytes()), wrapped)
            } finally {
                authKeySecret.destroy()
                kek.destroy()
            }
        } finally {
            master.destroy()
        }
    }
}
