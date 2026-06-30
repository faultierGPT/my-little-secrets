package app.mls.core.sync

import app.mls.core.api.ApiException
import app.mls.core.api.MlsApi
import app.mls.core.crypto.AccountKeyCrypto
import app.mls.core.crypto.B64
import app.mls.core.crypto.NoteCrypto
import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NoteDto
import app.mls.core.model.NotePayload
import app.mls.core.model.NotesResponse
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest
import app.mls.core.store.CacheSnapshot
import app.mls.core.store.CachedNote
import app.mls.core.store.InMemoryNoteStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The defensive partner to the server's optimistic-concurrency check: if a push is rejected with 409
 * (a genuinely simultaneous edit landed after our pull), the engine must re-pull, merge, and retry —
 * never drop the local edit. This reproduces that exact rejection deterministically.
 */
class SyncOccRecoveryTest {

    /**
     * A server whose copy of note "x" is already at revision 2 with an OLD timestamp, so a normal
     * since-cursor pull does NOT see it but a stale-base push is rejected — forcing the recovery path.
     */
    private class StaleServerApi(
        var ciphertext: String,
        var nonce: String,
        var scheme: Int,
        var revision: Long,
        private val updatedAt: Long,
    ) : MlsApi {
        override var token: String? = "t"
        var putAttempts = 0

        override suspend fun getNotes(since: Long): NotesResponse {
            val visible = if (updatedAt > since) listOf(NoteDto("x", ciphertext, nonce, scheme, updatedAt, false, revision)) else emptyList()
            return NotesResponse(visible, serverTime = 1_000)
        }

        override suspend fun putNote(id: String, req: PutNoteRequest): NoteDto {
            putAttempts++
            if (req.revision != revision) throw ApiException(409, "conflict", "revision conflict")
            revision += 1
            ciphertext = req.ciphertext; nonce = req.nonce; scheme = req.schemeVersion
            return NoteDto(id, ciphertext, nonce, scheme, updatedAt + revision, false, revision)
        }

        override suspend fun register(req: RegisterRequest) = Unit
        override suspend fun loginParams(email: String): LoginParamsResponse = error("unused")
        override suspend fun login(req: LoginRequest): LoginResponse = error("unused")
        override suspend fun logout() = Unit
        override suspend fun getAccountKey(): AccountKeyResponse = error("unused")
        override suspend fun changePassword(req: PasswordChangeRequest) = Unit
        override suspend fun deleteNote(id: String): NoteDto = error("unused")
    }

    @Test
    fun `a 409 on push triggers re-pull and retry, preserving the local edit`() = runTest {
        val accountKey = AccountKeyCrypto.generate()

        // The server already holds a DIFFERENT rev-2 value for "x", timestamped in the past.
        val serverSealed = NoteCrypto.encrypt(accountKey, "x", NotePayload("T", "remote-rev2"))
        val api = StaleServerApi(
            ciphertext = B64.encode(serverSealed.ciphertext),
            nonce = B64.encode(serverSealed.nonce),
            scheme = serverSealed.schemeVersion,
            revision = 2,
            updatedAt = 1, // older than the local cursor below
        )

        // Locally we have a newer dirty edit at the now-stale base revision 1, cursor ahead of the
        // server's timestamp so the first pull can't see the server's rev-2.
        val localSealed = NoteCrypto.encrypt(accountKey, "x", NotePayload("T", "local-edit"))
        val store = InMemoryNoteStore(
            CacheSnapshot(
                cursor = 100,
                notes = listOf(
                    CachedNote(
                        id = "x",
                        ciphertext = B64.encode(localSealed.ciphertext),
                        nonce = B64.encode(localSealed.nonce),
                        schemeVersion = localSealed.schemeVersion,
                        baseRevision = 1,
                        updatedAt = 1,
                        dirty = true,
                        locallyUpdatedAt = 200, // newer than the server edit -> last-write-wins keeps ours
                    ),
                ),
            ),
        )

        var clock = 1000L
        val engine = SyncEngine(api, store, accountKey, ConflictPolicy.LAST_WRITE_WINS, now = { ++clock }, newId = { "dup" })
        val res = engine.sync()

        // The push was first rejected (stale base), then retried after a full re-pull and succeeded.
        assertEquals(1, res.conflicts)
        assertEquals(1, res.pushed)
        assertEquals(2, api.putAttempts) // one rejected, one accepted
        assertEquals(3L, api.revision)

        // No silent loss: the local edit won (it was newer) and is what now lives on both sides.
        assertEquals("local-edit", engine.list().single().payload.body)
        assertEquals("local-edit", NoteCrypto.decrypt(accountKey, "x",
            app.mls.core.crypto.Sealed(B64.decode(api.ciphertext), B64.decode(api.nonce), api.scheme)).body)
    }
}
