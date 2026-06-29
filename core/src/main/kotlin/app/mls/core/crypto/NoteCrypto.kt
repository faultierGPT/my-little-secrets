package app.mls.core.crypto

import app.mls.core.model.NotePayload
import kotlinx.serialization.json.Json
import java.util.Arrays

/**
 * Encrypts/decrypts a note under the account key. The plaintext is the JSON of [NotePayload]
 * (`{ title, body, tags, schemaVersion }`). The note id is bound as associated data so a
 * ciphertext is cryptographically tied to its id — the server cannot move a blob between ids
 * undetected.
 */
object NoteCrypto {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true // forward-compat: tolerate fields added by newer clients
    }

    fun encrypt(accountKey: SecretBytes, noteId: String, payload: NotePayload): Sealed {
        val pt = json.encodeToString(NotePayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        try {
            val nonce = Sodium.randomBytes(Sodium.AEAD_NONCE_BYTES)
            val ct = Sodium.aeadEncrypt(pt, noteAad(noteId), nonce, accountKey.bytes())
            return Sealed(ct, nonce)
        } finally {
            Arrays.fill(pt, 0)
        }
    }

    fun decrypt(accountKey: SecretBytes, noteId: String, sealed: Sealed): NotePayload {
        val pt = Sodium.aeadDecrypt(sealed.ciphertext, noteAad(noteId), sealed.nonce, accountKey.bytes())
        try {
            return json.decodeFromString(NotePayload.serializer(), pt.decodeToString())
        } finally {
            Arrays.fill(pt, 0)
        }
    }

    private fun noteAad(noteId: String) =
        (CryptoScheme.AAD_NOTE_PREFIX + "|" + noteId).toByteArray(Charsets.UTF_8)
}
