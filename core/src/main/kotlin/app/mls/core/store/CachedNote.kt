package app.mls.core.store

import kotlinx.serialization.Serializable

/**
 * A note as cached locally: the SAME XChaCha20 ciphertext the server stores (so note CONTENT is
 * encrypted at rest), plus local-only sync metadata.
 *
 * - [dirty]: local changes not yet pushed.
 * - [deleted]: a tombstone (content cleared).
 * - [baseRevision]: the last server revision this client has reconciled (for conflict detection).
 * - [updatedAt]: the server's timestamp (or local time for never-synced notes).
 * - [locallyUpdatedAt]: local clock at the last local edit (used for last-write-wins).
 */
@Serializable
data class CachedNote(
    val id: String,
    val ciphertext: String,
    val nonce: String,
    val schemeVersion: Int,
    val deleted: Boolean = false,
    val baseRevision: Long = 0,
    val updatedAt: Long = 0,
    val dirty: Boolean = false,
    val locallyUpdatedAt: Long = 0,
)

/** The full local cache: the sync cursor + all cached notes (including tombstones). */
@Serializable
data class CacheSnapshot(
    val cursor: Long = 0,
    val notes: List<CachedNote> = emptyList(),
)
