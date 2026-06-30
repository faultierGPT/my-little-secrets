package app.mls.core.crypto

import app.mls.core.model.NotePayload
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PasswordChangeTest {
    private val params = KdfParams.TEST_FAST

    @Test
    fun `password change re-wraps the same account key without re-encrypting notes`() {
        val oldPw = "old-password".toByteArray()
        val material = CryptoCore.register(oldPw.copyOf(), params, withRecovery = false)
        val accountKeyBytes = material.accountKey.copyBytes()

        // A note encrypted BEFORE the password change.
        val note = NotePayload("T", "B")
        val sealed = NoteCrypto.encrypt(material.accountKey, "n1", note)

        // Change the password: re-wrap the SAME account key.
        val newPw = "new-better-password".toByteArray()
        val rewrap = CryptoCore.rewrapForNewPassword(material.accountKey, newPw.copyOf(), params)

        // New password recovers the SAME account key...
        val unlocked = CryptoCore.unlockWithPassword(newPw.copyOf(), rewrap.salt, rewrap.kdfParams, rewrap.wrappedAccountKey)
        assertArrayEquals(accountKeyBytes, unlocked.copyBytes())

        // ...and the pre-change note still decrypts (the data key never changed).
        assertEquals(note, NoteCrypto.decrypt(unlocked, "n1", sealed))

        // Old password no longer opens the new wrapped key.
        assertThrows<AeadAuthException> {
            CryptoCore.unlockWithPassword(oldPw.copyOf(), rewrap.salt, rewrap.kdfParams, rewrap.wrappedAccountKey)
        }

        // The auth credential rotated too.
        val newAuthKey = CryptoCore.deriveAuthKeyForLogin(newPw.copyOf(), rewrap.salt, rewrap.kdfParams).copyBytes()
        assertArrayEquals(rewrap.authKey.bytes(), newAuthKey)
        assertFalse(rewrap.authKey.bytes().contentEquals(material.authKey.bytes()))
    }
}
