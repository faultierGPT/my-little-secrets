package app.mls.core.store

import app.mls.core.crypto.AccountKeyCrypto
import app.mls.core.crypto.AeadAuthException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class EncryptedFileNoteStoreTest {

    @Test
    fun `round-trips a snapshot`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "cache.bin")
        val store = EncryptedFileNoteStore(file, AccountKeyCrypto.generate())
        val snap = CacheSnapshot(
            cursor = 42,
            notes = listOf(CachedNote("n1", "Y2lwaA==", "bm9uY2U=", 1, false, 3, 1000, false, 999)),
        )
        store.write(snap)
        assertEquals(snap, store.read())
    }

    @Test
    fun `the file is encrypted at rest — no plaintext ids or field names leak`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "cache.bin")
        EncryptedFileNoteStore(file, AccountKeyCrypto.generate())
            .write(CacheSnapshot(7, listOf(CachedNote("SECRET-MARKER-ID", "ct", "no", 1))))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("SECRET-MARKER-ID"), "note id leaked in plaintext")
        assertFalse(raw.contains("cursor"), "JSON field names leaked (file not encrypted)")
    }

    @Test
    fun `a different account key cannot read the cache`(@TempDir dir: Path) {
        val file = File(dir.toFile(), "cache.bin")
        EncryptedFileNoteStore(file, AccountKeyCrypto.generate()).write(CacheSnapshot(1, emptyList()))
        assertThrows<AeadAuthException> { EncryptedFileNoteStore(file, AccountKeyCrypto.generate()).read() }
    }

    @Test
    fun `a missing file reads as an empty snapshot`(@TempDir dir: Path) {
        val store = EncryptedFileNoteStore(File(dir.toFile(), "absent.bin"), AccountKeyCrypto.generate())
        assertEquals(CacheSnapshot(), store.read())
    }
}
