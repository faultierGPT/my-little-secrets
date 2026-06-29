package app.mls.core.sync

import app.mls.core.crypto.AccountKeyCrypto
import app.mls.core.crypto.SecretBytes
import app.mls.core.model.NotePayload
import app.mls.core.store.InMemoryNoteStore
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SyncEngineTest {

    // One shared monotonic clock for all engines + the fake server, so "later" is unambiguous.
    private var t = 1000L
    private fun tick(): Long = ++t

    private val accountKey: SecretBytes = AccountKeyCrypto.generate()

    private fun idGen(prefix: String): () -> String {
        var n = 0
        return { "$prefix-${++n}" }
    }

    private fun engine(
        api: FakeMlsApi,
        store: InMemoryNoteStore,
        policy: ConflictPolicy = ConflictPolicy.LAST_WRITE_WINS,
        prefix: String = "id",
    ) = SyncEngine(api, store, accountKey, policy, now = ::tick, newId = idGen(prefix))

    @Test
    fun `push then pull syncs a note to a second device`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore(), prefix = "a")
        val storeB = InMemoryNoteStore()
        val b = engine(api, storeB, prefix = "b")

        val id = a.save(NotePayload("Title", "Body A"))
        assertEquals(1, a.sync().pushed)

        assertEquals(1, b.sync().pulled)
        val onB = b.list().single()
        assertEquals(id, onB.id)
        assertEquals("Body A", onB.payload.body)
    }

    @Test
    fun `last-write-wins keeps the newer remote edit`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore())
        val b = engine(api, InMemoryNoteStore())
        val id = a.save(NotePayload("T", "v0")); a.sync(); b.sync()

        a.save(NotePayload("T", "vA-early"), id)         // local edit (earlier)
        b.save(NotePayload("T", "vB-late"), id); b.sync() // remote edit pushed (later)

        val res = a.sync()
        assertEquals(1, res.conflicts)
        assertEquals("vB-late", a.list().single { it.id == id }.payload.body)
    }

    @Test
    fun `last-write-wins keeps the newer local edit and pushes it`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore())
        val b = engine(api, InMemoryNoteStore())
        val id = a.save(NotePayload("T", "v0")); a.sync(); b.sync()

        b.save(NotePayload("T", "vB-early"), id); b.sync() // remote edit pushed (earlier)
        a.save(NotePayload("T", "vA-late"), id)            // local edit (later)

        assertEquals(1, a.sync().conflicts)
        assertEquals("vA-late", a.list().single { it.id == id }.payload.body)

        b.sync() // the local-wins edit propagated to the other device
        assertEquals("vA-late", b.list().single { it.id == id }.payload.body)
    }

    @Test
    fun `keep-both preserves both edits on conflict`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore(), ConflictPolicy.KEEP_BOTH, "a")
        val b = engine(api, InMemoryNoteStore(), ConflictPolicy.KEEP_BOTH, "b")
        val id = a.save(NotePayload("T", "v0")); a.sync(); b.sync()

        a.save(NotePayload("T", "vA"), id)
        b.save(NotePayload("T", "vB"), id); b.sync()

        val res = a.sync()
        assertEquals(1, res.conflicts)
        assertEquals(1, res.keptBoth)
        val bodies = a.list().map { it.payload.body }.toSet()
        assertTrue(bodies.contains("vA"), "local edit preserved")
        assertTrue(bodies.contains("vB"), "remote edit accepted")
        assertEquals(2, a.list().size)
    }

    @Test
    fun `delete syncs as a tombstone and removes the note on the other device`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore())
        val b = engine(api, InMemoryNoteStore())
        val id = a.save(NotePayload("T", "to-delete")); a.sync(); b.sync()
        assertEquals(1, b.list().size)

        a.delete(id)
        assertEquals(1, a.sync().deletedPushed)

        b.sync()
        assertTrue(b.list().isEmpty())
    }

    @Test
    fun `multiple offline edits push in a single sync`() = runTest {
        val api = FakeMlsApi(::tick)
        val a = engine(api, InMemoryNoteStore())
        a.save(NotePayload("1", "one"))
        a.save(NotePayload("2", "two"))
        a.save(NotePayload("3", "three"))
        assertEquals(3, a.sync().pushed)
    }
}
