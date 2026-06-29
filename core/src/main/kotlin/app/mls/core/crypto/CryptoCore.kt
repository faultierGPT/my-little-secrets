package app.mls.core.crypto

/** Material produced by local registration: what to send the server + what to show the user. */
class RegistrationMaterial(
    val salt: ByteArray,
    val kdfParams: KdfParams,
    val authKey: ByteArray,                 // sent to server; server stores Argon2id(authKey)
    val wrappedAccountKey: Sealed,
    val wrappedAccountKeyRecovery: Sealed?, // present iff recovery enabled
    val recoveryCode: RecoveryCode?,        // shown ONCE to the user, never persisted
    val accountKey: SecretBytes,            // unlocked, held in memory only
)

/** Result of re-wrapping the same account key for a new password. */
class RewrapResult(
    val salt: ByteArray,
    val kdfParams: KdfParams,
    val authKey: ByteArray,
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
                    val rwk = KeyHierarchy.deriveRecoveryWrapKey(rc.rawKey)
                    try {
                        wrappedRecovery = AccountKeyCrypto.wrapForRecovery(accountKey, rwk)
                    } finally {
                        rwk.destroy()
                    }
                    recoveryCode = rc
                }

                return RegistrationMaterial(
                    salt = salt,
                    kdfParams = params,
                    authKey = authKeySecret.copyBytes(),
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
        val rwk = KeyHierarchy.deriveRecoveryWrapKey(recovery.rawKey)
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
                return RewrapResult(salt, params, authKeySecret.copyBytes(), wrapped)
            } finally {
                authKeySecret.destroy()
                kek.destroy()
            }
        } finally {
            master.destroy()
        }
    }
}
