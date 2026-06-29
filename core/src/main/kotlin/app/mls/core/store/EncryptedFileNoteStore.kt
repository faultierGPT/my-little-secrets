package app.mls.core.store

import app.mls.core.crypto.SecretBytes
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Arrays

/**
 * Persists the cache to a single file, ENCRYPTED AT REST with a cache key derived from the account
 * key. Both note content AND local metadata (ids, flags, timestamps) are encrypted — the file is
 * meaningless without the unlocked account key. Writes are atomic (temp file + rename).
 *
 * Lives only while unlocked; call [close] (e.g. on auto-lock) to wipe the derived cache key.
 */
class EncryptedFileNoteStore(
    private val file: File,
    accountKey: SecretBytes,
) : NoteStore, AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheKey: SecretBytes = CacheCrypto.deriveCacheKey(accountKey)

    override fun read(): CacheSnapshot {
        if (!file.exists() || file.length() == 0L) return CacheSnapshot()
        val plain = CacheCrypto.open(file.readBytes(), cacheKey)
        try {
            return json.decodeFromString(CacheSnapshot.serializer(), plain.decodeToString())
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    override fun write(snapshot: CacheSnapshot) {
        val plain = json.encodeToString(CacheSnapshot.serializer(), snapshot).toByteArray()
        try {
            val blob = CacheCrypto.seal(plain, cacheKey)
            val dir = file.absoluteFile.parentFile
            dir?.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(blob)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    override fun close() = cacheKey.destroy()
}
