package app.mls.core.sync

import app.mls.core.api.ApiException
import app.mls.core.api.MlsApi
import app.mls.core.crypto.B64
import app.mls.core.crypto.NoteCrypto
import app.mls.core.crypto.Sealed
import app.mls.core.crypto.SecretBytes
import app.mls.core.model.NoteDto
import app.mls.core.model.NotePayload
import app.mls.core.model.PutNoteRequest
import app.mls.core.store.CacheSnapshot
import app.mls.core.store.CachedNote
import app.mls.core.store.NoteStore
import java.util.UUID

/** How to resolve a note edited on two devices since the last sync. */
enum class ConflictPolicy { LAST_WRITE_WINS, KEEP_BOTH }

/** A note decrypted for display. */
data class DecryptedNote(val id: String, val payload: NotePayload, val updatedAt: Long, val dirty: Boolean)

data class SyncResult(
    val pulled: Int = 0,
    val pushed: Int = 0,
    val deletedPushed: Int = 0,
    val conflicts: Int = 0,
    val keptBoth: Int = 0,
)

/**
 * Offline-first sync. Because the server stores only ciphertext it cannot merge — all reconciliation
 * happens here, on the client, with the [accountKey] in memory. Local edits are encrypted
 * immediately (nothing is plaintext at rest), and [sync] PULLs before it PUSHes so a concurrent
 * edit on another device is detected as a conflict instead of being silently overwritten.
 */
class SyncEngine(
    private val api: MlsApi,
    private val store: NoteStore,
    private val accountKey: SecretBytes,
    private val policy: ConflictPolicy = ConflictPolicy.LAST_WRITE_WINS,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    // ---------- local editing (works fully offline) ----------

    /** All non-deleted notes, decrypted for display. */
    fun list(): List<DecryptedNote> = store.read().notes
        .filter { !it.deleted }
        .map { DecryptedNote(it.id, decrypt(it), it.updatedAt, it.dirty) }

    /** Create (id == null) or update a note locally. Returns the note id. */
    fun save(payload: NotePayload, id: String? = null): String {
        val snap = store.read()
        val noteId = id ?: newId()
        val sealed = NoteCrypto.encrypt(accountKey, noteId, payload)
        val existing = snap.notes.firstOrNull { it.id == noteId }
        val updated = CachedNote(
            id = noteId,
            ciphertext = B64.encode(sealed.ciphertext),
            nonce = B64.encode(sealed.nonce),
            schemeVersion = sealed.schemeVersion,
            deleted = false,
            baseRevision = existing?.baseRevision ?: 0,
            updatedAt = existing?.updatedAt ?: now(),
            dirty = true,
            locallyUpdatedAt = now(),
        )
        store.write(snap.copy(notes = snap.notes.upsert(updated)))
        return noteId
    }

    /** Mark a note deleted locally (a tombstone to push). A never-synced note is just dropped. */
    fun delete(id: String) {
        val snap = store.read()
        val existing = snap.notes.firstOrNull { it.id == id } ?: return
        val notes = if (existing.baseRevision == 0L) {
            snap.notes.filterNot { it.id == id } // never reached the server
        } else {
            snap.notes.upsert(existing.copy(deleted = true, ciphertext = "", nonce = "", dirty = true, locallyUpdatedAt = now()))
        }
        store.write(snap.copy(notes = notes))
    }

    // ---------- sync ----------

    suspend fun sync(): SyncResult {
        val pull = pull()      // detect conflicts before overwriting the server
        val push = push()
        return pull.copy(pushed = push.pushed, deletedPushed = push.deletedPushed)
    }

    private suspend fun pull(): SyncResult {
        val snap = store.read()
        val resp = api.getNotes(snap.cursor)
        var notes = snap.notes
        var conflicts = 0
        var keptBoth = 0
        for (server in resp.notes) {
            val local = notes.firstOrNull { it.id == server.id }
            val r = merge(local, server)
            notes = notes.upsert(r.updatedSelf)
            r.duplicate?.let { notes = notes + it }
            if (r.conflict) conflicts++
            if (r.keptBoth) keptBoth++
        }
        store.write(CacheSnapshot(cursor = resp.serverTime, notes = notes))
        return SyncResult(pulled = resp.notes.size, conflicts = conflicts, keptBoth = keptBoth)
    }

    private suspend fun push(): SyncResult {
        var snap = store.read()
        var pushed = 0
        var deletedPushed = 0
        for (note in snap.notes.filter { it.dirty }) {
            if (note.deleted) {
                val dto = try {
                    api.deleteNote(note.id)
                } catch (e: ApiException) {
                    if (e.isNotFound) null else throw e // already gone server-side
                }
                snap = snap.copy(
                    notes = snap.notes.upsert(
                        note.copy(dirty = false, baseRevision = dto?.revision ?: note.baseRevision, updatedAt = dto?.updatedAt ?: note.updatedAt),
                    ),
                )
                deletedPushed++
            } else {
                val dto = api.putNote(note.id, PutNoteRequest(note.ciphertext, note.nonce, note.schemeVersion, note.baseRevision))
                snap = snap.copy(notes = snap.notes.upsert(note.copy(dirty = false, baseRevision = dto.revision, updatedAt = dto.updatedAt)))
                pushed++
            }
        }
        store.write(snap)
        return SyncResult(pushed = pushed, deletedPushed = deletedPushed)
    }

    // ---------- merge (pure; the heart of conflict resolution) ----------

    private class MergeResult(
        val updatedSelf: CachedNote,
        val duplicate: CachedNote? = null,
        val conflict: Boolean = false,
        val keptBoth: Boolean = false,
    )

    private fun merge(local: CachedNote?, server: NoteDto): MergeResult {
        val serverAsCache = server.toCached()

        // No local copy, or local has no pending edits -> the server is authoritative.
        if (local == null || !local.dirty) return MergeResult(serverAsCache)

        // Local is dirty but the server has NOT advanced past our base -> keep our edit; we'll push it.
        if (server.revision <= local.baseRevision) return MergeResult(local)

        // True conflict: both sides changed since the last sync.
        return when (policy) {
            ConflictPolicy.LAST_WRITE_WINS ->
                if (server.updatedAt >= local.locallyUpdatedAt) {
                    MergeResult(serverAsCache, conflict = true) // server edit is newer -> it wins
                } else {
                    // local edit is newer -> keep it, but rebase so our push cleanly overwrites the server
                    MergeResult(local.copy(baseRevision = server.revision), conflict = true)
                }

            ConflictPolicy.KEEP_BOTH -> {
                // Accept the server version into the original id; preserve the local edit as a NEW note.
                val duplicate = if (!local.deleted) cloneAsNew(local) else null
                MergeResult(serverAsCache, duplicate = duplicate, conflict = true, keptBoth = duplicate != null)
            }
        }
    }

    /** Re-encrypt a local note's content under a brand-new id (AAD binds ciphertext to its id). */
    private fun cloneAsNew(local: CachedNote): CachedNote {
        val payload = decrypt(local)
        val id = newId()
        val sealed = NoteCrypto.encrypt(accountKey, id, payload)
        return CachedNote(
            id = id,
            ciphertext = B64.encode(sealed.ciphertext),
            nonce = B64.encode(sealed.nonce),
            schemeVersion = sealed.schemeVersion,
            deleted = false,
            baseRevision = 0,
            updatedAt = now(),
            dirty = true,
            locallyUpdatedAt = now(),
        )
    }

    private fun decrypt(note: CachedNote): NotePayload =
        NoteCrypto.decrypt(accountKey, note.id, Sealed(B64.decode(note.ciphertext), B64.decode(note.nonce), note.schemeVersion))

    private fun NoteDto.toCached() = CachedNote(
        id = id,
        ciphertext = ciphertext,
        nonce = nonce,
        schemeVersion = schemeVersion,
        deleted = deleted,
        baseRevision = revision,
        updatedAt = updatedAt,
        dirty = false,
        locallyUpdatedAt = 0,
    )
}

private fun List<CachedNote>.upsert(note: CachedNote): List<CachedNote> {
    val idx = indexOfFirst { it.id == note.id }
    return if (idx >= 0) toMutableList().also { it[idx] = note } else this + note
}
