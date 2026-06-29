package app.mls.core.model

import kotlinx.serialization.Serializable

/**
 * The plaintext note that gets encrypted. Exists ONLY on the client — the server never sees
 * this shape, only its ciphertext. [schemaVersion] mirrors `CryptoScheme.NOTE_SCHEMA_VERSION`
 * (kept as a literal here to avoid a model → crypto dependency cycle).
 */
@Serializable
data class NotePayload(
    val title: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val schemaVersion: Int = 1,
)
