package app.mls.core.crypto

import java.io.ByteArrayOutputStream
import java.util.Base64

/** Base64 (standard, padded) for wire encoding of ciphertext/nonce/salt blobs. */
object B64 {
    private val enc = Base64.getEncoder()
    private val dec = Base64.getDecoder()
    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray = dec.decode(s)
}

/**
 * RFC 4648 Base32 (no padding), used to render the recovery code as a human-typable string.
 * Case-insensitive on decode; callers strip grouping dashes/spaces first.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(ALPHABET[(buffer ushr bits) and 0x1F])
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 0x1F])
        return sb.toString()
    }

    fun decode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in s.uppercase()) {
            val v = ALPHABET.indexOf(c)
            require(v >= 0) { "invalid base32 character: '$c'" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
