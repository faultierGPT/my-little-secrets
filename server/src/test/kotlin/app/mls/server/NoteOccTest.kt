package app.mls.server

import app.mls.server.db.NoteRepository
import app.mls.server.db.UserRecord
import app.mls.server.db.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * Regression for the confirmed review finding "server ignores client baseRevision (OCC bypass) —
 * concurrent edits silently lost". The repository now enforces optimistic concurrency with a
 * compare-and-swap on `revision`, so a stale base revision is rejected instead of overwriting.
 */
class NoteOccTest {

    @Test
    fun `optimistic concurrency rejects a stale base revision and preserves the winning write`() {
        val db = ServerTestSupport.freshH2Db()
        try {
            val users = UserRepository(db)
            val notes = NoteRepository(db)
            val uid = UUID.randomUUID().toString()
            users.insert(UserRecord(uid, "occ@example.com", "salt", "{}", "verifier", "wrapped", null, 1, 1_000))

            // Create (base 0) then a normal update (base 1) — both succeed and bump the revision.
            assertEquals(1, notes.upsert(uid, "n1", "ct1", "no1", 1, 0, 1_000).revision)
            assertEquals(2, notes.upsert(uid, "n1", "ct2", "no2", 1, 1, 1_001).revision)

            // A second device still at base revision 1 (server is at 2) loses the race -> conflict.
            assertThrows<NoteRepository.RevisionConflictException> {
                notes.upsert(uid, "n1", "ct3-should-be-rejected", "no3", 1, 1, 1_002)
            }
            // A brand-new id that claims a non-zero base is also a conflict (won't resurrect at rev 1).
            assertThrows<NoteRepository.RevisionConflictException> {
                notes.upsert(uid, "n2", "x", "y", 1, 5, 1_003)
            }

            // The rejected write never landed: the stored content is still the rev-2 write.
            val stored = notes.listSince(uid, 0).single { it.id == "n1" }
            assertEquals("ct2", stored.ciphertext)
            assertEquals(2, stored.revision)
        } finally {
            db.close()
        }
    }
}
