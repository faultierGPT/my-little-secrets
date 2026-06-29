package app.mls.core.crypto

/**
 * Sensitive key material held as a wipeable `byte[]` — NEVER a `String` (Strings are immutable
 * and linger in the heap until GC, with no way to scrub them).
 *
 * Zeroization is best-effort: the JVM and JNI may make copies we cannot reach (GC relocation,
 * native argument marshalling). We wipe every array we own the instant it is no longer needed.
 * This is documented honestly in SECURITY.md as defence-in-depth, not a hard guarantee.
 *
 * Implements [AutoCloseable] so callers can use `derive(...).use { ... }`.
 */
class SecretBytes private constructor(private val data: ByteArray) : AutoCloseable {

    @Volatile
    private var alive = true

    val size: Int get() = data.size

    /** Live reference for passing into libsodium. Do not retain it past the call. */
    fun bytes(): ByteArray {
        check(alive) { "SecretBytes already destroyed" }
        return data
    }

    /** Independent copy; the caller owns and should wipe it. */
    fun copyBytes(): ByteArray = bytes().copyOf()

    fun destroy() {
        if (alive) {
            Sodium.memzero(data)
            alive = false
        }
    }

    override fun close() = destroy()

    companion object {
        /** Wraps and TAKES OWNERSHIP of [array]; the caller must not reuse it afterwards. */
        fun wrap(array: ByteArray): SecretBytes = SecretBytes(array)

        /** Copies [array]; the caller keeps ownership of the original. */
        fun copyOf(array: ByteArray): SecretBytes = SecretBytes(array.copyOf())
    }
}
