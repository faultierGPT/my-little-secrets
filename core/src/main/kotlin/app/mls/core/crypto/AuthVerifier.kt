package app.mls.core.crypto

/**
 * Server-side credential verifier: `authVerifier = Argon2id(authKey)`, stored as a self-
 * describing PHC string (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`). A DB leak therefore
 * yields no usable credential — an attacker would have to reverse Argon2id over a 256-bit
 * input, which is infeasible. Verification is constant-time (libsodium's `*_str_verify`).
 *
 * Lives in the shared core (so the contract is defined once) but is USED only by the server.
 * `authKey` is already 256-bit high-entropy, so a moderate Argon2id profile keeps login cheap
 * while still hardening the at-rest verifier.
 */
object AuthVerifier {
    private const val OPS = 3L          // MODERATE
    private const val MEM = 67_108_864L // 64 MiB — input is high-entropy, not a weak password

    /** Compute the stored verifier for a freshly-registered (or rotated) authKey. */
    fun create(authKey: ByteArray): String = Sodium.pwhashStr(authKey, OPS, MEM)

    /** Constant-time check of a presented authKey against the stored verifier. */
    fun verify(storedVerifier: String, authKey: ByteArray): Boolean =
        Sodium.pwhashStrVerify(storedVerifier, authKey)
}
