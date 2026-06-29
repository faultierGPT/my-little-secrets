package app.mls.core.crypto

/**
 * A 256-bit recovery secret, shown to the user as a grouped Base32 string. Its 32 raw bytes are
 * the crypto_kdf master for the recovery wrapping key (see [KeyHierarchy.deriveRecoveryWrapKey]).
 *
 * This is the ONLY path to recover the account key if the master password is lost — and it is an
 * additional high-value secret the user must store safely. Surfacing that tradeoff is a UI
 * responsibility; this type only generates, renders, and parses the code.
 */
class RecoveryCode private constructor(val rawKey: ByteArray) {

    /** Human-facing form, e.g. `ABCDE-FGHIJ-...` (Base32, dash-grouped in fives). */
    fun display(): String = Base32.encode(rawKey).chunked(5).joinToString("-")

    companion object {
        const val KEY_BYTES = 32

        fun generate(): RecoveryCode = RecoveryCode(Sodium.randomBytes(KEY_BYTES))

        /** Parse user-entered text (tolerant of case, dashes and spaces). */
        fun parse(input: String): RecoveryCode {
            val cleaned = input.trim().replace("-", "").replace(" ", "")
            val raw = Base32.decode(cleaned)
            require(raw.size == KEY_BYTES) { "recovery code must decode to $KEY_BYTES bytes, got ${raw.size}" }
            return RecoveryCode(raw)
        }
    }
}
