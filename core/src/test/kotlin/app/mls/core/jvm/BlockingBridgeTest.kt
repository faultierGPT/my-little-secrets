package app.mls.core.jvm

import app.mls.core.crypto.AccountKeyCrypto
import app.mls.core.model.NotePayload
import app.mls.core.model.PutNoteRequest
import app.mls.core.store.InMemoryNoteStore
import app.mls.core.sync.FakeMlsApi
import app.mls.core.sync.SyncEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure-Java desktop client reaches the suspending API only through these blocking adapters, so
 * they must faithfully run the coroutine to completion and surface its result/exception. Exercised
 * here against the same in-memory [FakeMlsApi] the sync tests use.
 */
class BlockingBridgeTest {

    private var clock = 1_000L
    private fun tick() = ++clock

    @Test
    fun `BlockingApi forwards suspending calls synchronously`() {
        val api = FakeMlsApi { clock }
        val blocking = BlockingApi(api)

        // token property mirrors the wrapped api
        blocking.token = "session-xyz"
        assertEquals("session-xyz", api.token)
        assertEquals("session-xyz", blocking.token)

        // a write then a read, all synchronous from the caller's perspective
        val put = blocking.putNote("n1", PutNoteRequest("ct", "nonce", 1, 0))
        assertEquals(1L, put.revision)
        val notes = blocking.getNotes(0).notes
        assertEquals(1, notes.size)
        assertEquals("n1", notes.first().id)
    }

    @Test
    fun `BlockingSync drives SyncEngine sync to completion`() {
        val api = FakeMlsApi { tick() }
        val store = InMemoryNoteStore()
        AccountKeyCrypto.generate().use { accountKey ->
            val engine = SyncEngine(api, store, accountKey, now = { clock })

            // local-only edit (synchronous), then a blocking sync that must push it
            engine.save(NotePayload(title = "Hello", body = "world"))
            val result = BlockingSync.sync(engine)

            assertEquals(1, result.pushed)
            assertEquals(0, result.conflicts)
            // and it is now present + clean on the next read
            val listed = engine.list()
            assertEquals(1, listed.size)
            assertEquals("Hello", listed.first().payload.title)
            assertTrue(!listed.first().dirty)
        }
    }
}
