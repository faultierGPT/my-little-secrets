package app.mls.core.crypto

import app.mls.core.model.NotePayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AeadTest {
    private val accountKey = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
    private val noteId = "note-123"
    private val payload = NotePayload(title = "Title", body = "Secret body", tags = listOf("x", "y"))

    @Test
    fun `encrypt then decrypt round-trips the payload`() {
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        assertEquals(payload, NoteCrypto.decrypt(accountKey, noteId, sealed))
    }

    @Test
    fun `nonce is fresh per encryption (no reuse)`() {
        val a = NoteCrypto.encrypt(accountKey, noteId, payload)
        val b = NoteCrypto.encrypt(accountKey, noteId, payload)
        assertFalse(a.nonce.contentEquals(b.nonce), "nonces must never repeat")
        assertFalse(a.ciphertext.contentEquals(b.ciphertext), "same plaintext must not yield same ciphertext")
    }

    @Test
    fun `tampered ciphertext fails authentication`() {
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        sealed.ciphertext[0] = (sealed.ciphertext[0].toInt() xor 0x01).toByte()
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(accountKey, noteId, sealed) }
    }

    @Test
    fun `tampered nonce fails authentication`() {
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        sealed.nonce[0] = (sealed.nonce[0].toInt() xor 0x01).toByte()
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(accountKey, noteId, sealed) }
    }

    @Test
    fun `wrong key fails authentication`() {
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        val wrongKey = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(wrongKey, noteId, sealed) }
    }

    @Test
    fun `wrong note id (associated data) fails authentication`() {
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(accountKey, "different-id", sealed) }
    }
}
