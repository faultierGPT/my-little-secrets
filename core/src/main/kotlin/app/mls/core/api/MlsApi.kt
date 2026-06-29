package app.mls.core.api

import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NoteDto
import app.mls.core.model.NotesResponse
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest

/**
 * Typed contract for the sync server. Implemented by [KtorApiClient] for production and faked in
 * tests, so the [app.mls.core.sync.SyncEngine] can be exercised without a network.
 *
 * Every call sends and receives ONLY ciphertext + metadata — by construction this interface has
 * no surface that could carry note plaintext or a password-derived secret other than `authKey`.
 */
interface MlsApi {
    /** Bearer session token; set by [login], cleared by [logout]. Authenticated calls require it. */
    var token: String?

    suspend fun register(req: RegisterRequest)
    suspend fun loginParams(email: String): LoginParamsResponse
    suspend fun login(req: LoginRequest): LoginResponse
    suspend fun logout()

    suspend fun getAccountKey(): AccountKeyResponse
    suspend fun changePassword(req: PasswordChangeRequest)

    suspend fun getNotes(since: Long): NotesResponse
    suspend fun putNote(id: String, req: PutNoteRequest): NoteDto
    suspend fun deleteNote(id: String): NoteDto
}

/** Non-2xx response from the server. [status] is the HTTP code; [code] is the machine-readable error. */
class ApiException(val status: Int, val code: String?, message: String) : RuntimeException(message) {
    val isUnauthorized get() = status == 401
    val isNotFound get() = status == 404
    val isConflict get() = status == 409
    val isRateLimited get() = status == 429
}
