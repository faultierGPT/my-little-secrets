package app.mls.android.session

/** A note decrypted for display — exists only in memory while the vault is unlocked. */
data class NoteUi(
    val id: String,
    val title: String,
    val body: String,
    val tags: List<String>,
    val updatedAt: Long,
    val dirty: Boolean,
) {
    val displayTitle: String get() = title.ifBlank { "Untitled" }
    val snippet: String
        get() {
            val basis = if (body.isNotBlank()) body else tags.joinToString(" ")
            val oneLine = basis.replace(Regex("\\s+"), " ").trim()
            return if (oneLine.length <= 80) oneLine else oneLine.take(79) + "…"
        }
}

/** Outcome of a sync round, for a concise status line. */
data class SyncSummary(
    val pulled: Int,
    val pushed: Int,
    val deletedPushed: Int,
    val conflicts: Int,
    val keptBoth: Int,
) {
    fun describe(): String = when {
        conflicts > 0 -> "Synced · $pulled in, ${pushed + deletedPushed} out, $conflicts merged"
        pulled == 0 && pushed == 0 && deletedPushed == 0 -> "Up to date"
        else -> "Synced · $pulled in, ${pushed + deletedPushed} out"
    }
}

/** A user-facing failure whose message is safe to display (never contains secrets or ciphertext). */
class VaultException(message: String) : RuntimeException(message)
