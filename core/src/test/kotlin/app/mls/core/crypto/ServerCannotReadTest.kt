package app.mls.core.crypto

import app.mls.core.model.NotePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Crypto-layer proof of the zero-knowledge property: from EXACTLY the set of bytes the server
 * stores, note plaintext is unrecoverable. Phase 2 adds the over-the-wire version against a
 * running server; this nails it down at the boundary that actually matters — the crypto.
 */
class ServerCannotReadTest {
    private val params = KdfParams.TEST_FAST

    @Test
    fun `the stored server record reveals nothing about note content`() {
        val password = "the-master-password".toByteArray()
        val secretBody = "Dinner with Alex, 7pm, the usual place"
        val note = NotePayload("Private", secretBody, listOf("personal"))

        val material = CryptoCore.register(password.copyOf(), params, withRecovery = true)
        val sealed = NoteCrypto.encrypt(material.accountKey, "n1", note)

        // ---- EXACTLY what the server database holds, and nothing more ----
        val storedSalt = material.salt
        val storedParams = material.kdfParams
        val storedAuthVerifier = AuthVerifier.create(material.authKey)
        val storedWrappedAccountKey = material.wrappedAccountKey.toBlob()
        val storedNoteBlob = sealed.toBlob()

        // 1) The ciphertext does not contain the plaintext, in any obvious encoding.
        val rawCt = B64.decode(storedNoteBlob.ciphertext)
        assertFalse(String(rawCt, Charsets.ISO_8859_1).contains(secretBody))
        assertFalse(storedNoteBlob.ciphertext.contains(B64.encode(secretBody.toByteArray())))

        // 2) An attacker holding the server data + a guessed random account key cannot decrypt.
        val attackerKey = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(attackerKey, "n1", Sealed.fromBlob(storedNoteBlob)) }

        // 3) The wrapped account key cannot be opened without the password-derived KEK.
        assertThrows<AeadAuthException> { AccountKeyCrypto.unwrap(Sealed.fromBlob(storedWrappedAccountKey), attackerKey) }

        // 4) The auth verifier is a one-way hash, not a usable key.
        assertTrue(storedAuthVerifier.startsWith("\$argon2id\$"))

        // 5) Sanity: WITH the password (which the server never sees) everything works.
        val unlocked = CryptoCore.unlockWithPassword(
            password.copyOf(), storedSalt, storedParams, Sealed.fromBlob(storedWrappedAccountKey),
        )
        assertEquals(note, NoteCrypto.decrypt(unlocked, "n1", Sealed.fromBlob(storedNoteBlob)))
    }
}
