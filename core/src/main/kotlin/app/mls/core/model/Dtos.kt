package app.mls.core.model

import app.mls.core.crypto.KdfParams
import kotlinx.serialization.Serializable

/**
 * Wire DTOs shared end-to-end (client `api/` ⇄ server `routes/`). Defining them once in `core`
 * removes a whole class of contract drift. NOTHING here carries plaintext note content or any
 * password-derived secret beyond `authKey` (which the server immediately converts to an Argon2id
 * verifier and never stores — see SECURITY.md §2.4 and §4).
 */

@Serializable
data class RegisterRequest(
    val email: String,
    val salt: String,                                  // base64
    val kdfParams: KdfParams,
    val authKey: String,                               // base64; server stores ONLY Argon2id(authKey)
    val wrappedAccountKey: EncryptedBlob,
    val wrappedAccountKeyRecovery: EncryptedBlob? = null,
    val schemeVersion: Int = 1,
)

@Serializable
data class LoginParamsRequest(val email: String)

@Serializable
data class LoginParamsResponse(val salt: String, val kdfParams: KdfParams)

@Serializable
data class LoginRequest(val email: String, val authKey: String) // authKey base64

@Serializable
data class LoginResponse(val token: String, val expiresAt: Long)

@Serializable
data class AccountKeyResponse(
    val wrappedAccountKey: EncryptedBlob,
    val wrappedAccountKeyRecovery: EncryptedBlob? = null,
    val schemeVersion: Int,
)

/** Re-wrap on password change: same accountKey, new wrapping + new credential. */
@Serializable
data class PasswordChangeRequest(
    val currentAuthKey: String,                        // base64; authorizes the change
    val newSalt: String,                               // base64
    val newKdfParams: KdfParams,
    val newAuthKey: String,                            // base64
    val newWrappedAccountKey: EncryptedBlob,
)

/** A note as stored/synced — opaque ciphertext + metadata only. */
@Serializable
data class NoteDto(
    val id: String,
    val ciphertext: String,                            // base64
    val nonce: String,                                 // base64
    val schemeVersion: Int,
    val updatedAt: Long,                               // server-assigned epoch millis
    val deleted: Boolean,
    val revision: Long,
)

@Serializable
data class NotesResponse(val notes: List<NoteDto>, val serverTime: Long)

/** Client upsert of an encrypted note. `revision` is the client's last-known revision (OCC). */
@Serializable
data class PutNoteRequest(
    val ciphertext: String,
    val nonce: String,
    val schemeVersion: Int = 1,
    val revision: Long = 0,
)

@Serializable
data class ErrorResponse(val error: String, val code: String? = null)
