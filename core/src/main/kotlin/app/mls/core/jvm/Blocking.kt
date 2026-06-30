package app.mls.core.jvm

import app.mls.core.api.MlsApi
import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NoteDto
import app.mls.core.model.NotesResponse
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest
import app.mls.core.sync.SyncEngine
import app.mls.core.sync.SyncResult
import kotlinx.coroutines.runBlocking

/**
 * Blocking adapter over the suspending [MlsApi], for JVM callers that cannot invoke Kotlin
 * `suspend` functions directly — specifically the **pure-Java desktop client**. Keeping this bridge
 * in `core` (rather than reimplementing coroutine interop in Java) is what lets the desktop module
 * stay 100% Java with no coroutine plumbing of its own.
 *
 * Every call runs the underlying coroutine to completion on the CALLING thread. Callers therefore
 * MUST invoke these off any UI thread (the desktop runs them on a background executor).
 *
 * This adapter forwards the SAME [api] instance the caller also hands to [SyncEngine], so the bearer
 * [token] that [login] sets is shared by subsequent authenticated note calls.
 */
class BlockingApi(private val api: MlsApi) {

    /** Shared bearer token; mirrors [MlsApi.token]. */
    var token: String?
        get() = api.token
        set(value) {
            api.token = value
        }

    fun register(req: RegisterRequest) = runBlocking { api.register(req) }
    fun loginParams(email: String): LoginParamsResponse = runBlocking { api.loginParams(email) }
    fun login(req: LoginRequest): LoginResponse = runBlocking { api.login(req) }
    fun logout() = runBlocking { api.logout() }
    fun getAccountKey(): AccountKeyResponse = runBlocking { api.getAccountKey() }
    fun changePassword(req: PasswordChangeRequest) = runBlocking { api.changePassword(req) }
    fun getNotes(since: Long): NotesResponse = runBlocking { api.getNotes(since) }
    fun putNote(id: String, req: PutNoteRequest): NoteDto = runBlocking { api.putNote(id, req) }
    fun deleteNote(id: String): NoteDto = runBlocking { api.deleteNote(id) }
}

/**
 * Blocking wrapper over [SyncEngine.sync] (the only suspending SyncEngine operation; `list`/`save`/
 * `delete` are already synchronous and can be called from Java directly).
 */
object BlockingSync {
    @JvmStatic
    fun sync(engine: SyncEngine): SyncResult = runBlocking { engine.sync() }
}
