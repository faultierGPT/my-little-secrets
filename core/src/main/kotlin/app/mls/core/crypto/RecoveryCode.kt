package app.mls.core.crypto

/**
 * A 256-bit recovery secret, shown to the user as a grouped Base32 string. Its 32 raw bytes are
 * the crypto_kdf master for the recovery wrapping key (see [KeyHierarchy.deriveRecoveryWrapKey]).
 *
 * This is the ONLY path to recover the account key if the master password is lost — and it is an
 * additional high-value secret the user must store safely. Surfacing that tradeoff is a UI
 * responsibility; this type only generates, renders, and parses the code.
 *
 * Because the raw bytes are a password-equivalent, they are held in a wipeable [SecretBytes], not a
 * bare array. The owner (the screen that shows or consumes the code) MUST [close] it once done —
 * e.g. `RecoveryCode.parse(input).use { core.unlockWithRecovery(it, blob) }`. The Base32 [display]
 * String is unavoidable (it has to be shown) but should be held only for the moment it is on screen.
 */
class RecoveryCode private constructor(private val secret: SecretBytes) : AutoCloseable {

    /** Live 32-byte reference for deriving the recovery wrap key. Do not retain it past the call. */
    fun rawKey(): ByteArray = secret.bytes()

    /** Human-facing form, e.g. `ABCDE-FGHIJ-...` (Base32, dash-grouped in fives). */
    fun display(): String = Base32.encode(secret.bytes()).chunked(5).joinToString("-")

    /** Wipe the raw bytes. Idempotent. */
    fun destroy() = secret.destroy()
    override fun close() = destroy()

    companion object {
        const val KEY_BYTES = 32

        fun generate(): RecoveryCode = RecoveryCode(SecretBytes.wrap(Sodium.randomBytes(KEY_BYTES)))

        /** Parse user-entered text (tolerant of case, dashes and spaces). */
        fun parse(input: String): RecoveryCode {
            val cleaned = input.trim().replace("-", "").replace(" ", "")
            val raw = Base32.decode(cleaned)
            require(raw.size == KEY_BYTES) { "recovery code must decode to $KEY_BYTES bytes, got ${raw.size}" }
            return RecoveryCode(SecretBytes.wrap(raw)) // SecretBytes takes ownership of the decoded array
        }
    }
}
