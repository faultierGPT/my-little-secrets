package app.mls.core.model

import kotlinx.serialization.Serializable

/** Wire form of a single AEAD output: base64 ciphertext (incl. tag) + base64 nonce + scheme. */
@Serializable
data class EncryptedBlob(
    val ciphertext: String,
    val nonce: String,
    val schemeVersion: Int,
)
