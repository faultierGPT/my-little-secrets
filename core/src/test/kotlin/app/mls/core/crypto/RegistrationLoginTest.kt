package app.mls.core.crypto

import app.mls.core.model.NotePayload
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** End-to-end exercise of the register → login → unlock → note round-trip flow. */
class RegistrationLoginTest {
    private val params = KdfParams.TEST_FAST

    @Test
    fun `register, login, unlock, and note round-trip succeed for the right password`() {
        val password = "S3cret-Master-Pass!".toByteArray()
        val material = CryptoCore.register(password.copyOf(), params, withRecovery = false)

        // The server stores Argon2id(authKey), never authKey itself.
        val storedVerifier = AuthVerifier.create(material.authKey)

        // Login: client re-derives authKey from password + server-provided salt/params.
        val loginAuthKey = CryptoCore.deriveAuthKeyForLogin(password.copyOf(), material.salt, material.kdfParams)
        assertArrayEquals(material.authKey, loginAuthKey.copyBytes())
        assertTrue(AuthVerifier.verify(storedVerifier, loginAuthKey.copyBytes()))

        // Unlock yields the same in-memory account key.
        val unlocked = CryptoCore.unlockWithPassword(
            password.copyOf(), material.salt, material.kdfParams, material.wrappedAccountKey,
        )
        assertArrayEquals(material.accountKey.copyBytes(), unlocked.copyBytes())

        // A note encrypted under the account key decrypts after a fresh unlock.
        val note = NotePayload("Title", "Body text", listOf("a", "b"))
        val sealed = NoteCrypto.encrypt(material.accountKey, "n1", note)
        assertEquals(note, NoteCrypto.decrypt(unlocked, "n1", sealed))
    }

    @Test
    fun `wrong password neither authenticates nor unlocks`() {
        val material = CryptoCore.register("rightpass".toByteArray(), params, withRecovery = false)
        val storedVerifier = AuthVerifier.create(material.authKey)

        val wrongAuthKey = CryptoCore
            .deriveAuthKeyForLogin("wrongpass".toByteArray(), material.salt, material.kdfParams)
            .copyBytes()

        assertFalse(wrongAuthKey.contentEquals(material.authKey))
        assertFalse(AuthVerifier.verify(storedVerifier, wrongAuthKey))
        assertThrows<AeadAuthException> {
            CryptoCore.unlockWithPassword("wrongpass".toByteArray(), material.salt, material.kdfParams, material.wrappedAccountKey)
        }
    }
}
