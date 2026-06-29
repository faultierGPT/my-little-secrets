package app.mls.core.sync

import app.mls.core.api.ApiException
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

/**
 * In-memory stand-in for the server, mirroring its note semantics (server-incremented revisions,
 * server-assigned timestamps, since-filtered listing). Lets [SyncEngine] be tested without a network.
 */
class FakeMlsApi(private val clock: () -> Long) : MlsApi {
    override var token: String? = "fake-token"

    private data class Row(val ciphertext: String, val nonce: String, val schemeVersion: Int, val updatedAt: Long, val deleted: Boolean, val revision: Long)
    private val notes = LinkedHashMap<String, Row>()

    override suspend fun register(req: RegisterRequest) = Unit
    override suspend fun loginParams(email: String): LoginParamsResponse = error("not used in sync tests")
    override suspend fun login(req: LoginRequest): LoginResponse = error("not used in sync tests")
    override suspend fun logout() = Unit
    override suspend fun getAccountKey(): AccountKeyResponse = error("not used in sync tests")
    override suspend fun changePassword(req: PasswordChangeRequest) = Unit

    override suspend fun getNotes(since: Long): NotesResponse {
        val serverTime = clock()
        val list = notes.entries
            .filter { it.value.updatedAt > since }
            .map { (id, r) -> NoteDto(id, r.ciphertext, r.nonce, r.schemeVersion, r.updatedAt, r.deleted, r.revision) }
            .sortedBy { it.updatedAt }
        return NotesResponse(list, serverTime)
    }

    override suspend fun putNote(id: String, req: PutNoteRequest): NoteDto {
        val rev = (notes[id]?.revision ?: 0) + 1
        val row = Row(req.ciphertext, req.nonce, req.schemeVersion, clock(), false, rev)
        notes[id] = row
        return NoteDto(id, row.ciphertext, row.nonce, row.schemeVersion, row.updatedAt, false, rev)
    }

    override suspend fun deleteNote(id: String): NoteDto {
        val existing = notes[id] ?: throw ApiException(404, "not_found", "no such note")
        val rev = existing.revision + 1
        val row = Row("", "", existing.schemeVersion, clock(), true, rev)
        notes[id] = row
        return NoteDto(id, "", "", row.schemeVersion, row.updatedAt, true, rev)
    }
}
