package app.mls.core.crypto

import kotlinx.serialization.Serializable

/**
 * Argon2id cost parameters. Stored server-side per user (public, non-secret) so they can be
 * UPGRADED later without breaking existing accounts, and negotiated lower on constrained
 * devices. libsodium's `crypto_pwhash` fixes internal parallelism at 1; [parallelism] is
 * recorded for completeness and forward-compatibility.
 */
@Serializable
data class KdfParams(
    val algorithm: String = "argon2id",
    val memLimitBytes: Long,
    val opsLimit: Long,
    val parallelism: Int = 1,
) {
    init {
        require(algorithm == "argon2id") { "scheme v1 supports only argon2id" }
        require(memLimitBytes >= 8192) { "memLimitBytes below libsodium minimum (8192)" }
        require(opsLimit >= 1) { "opsLimit below libsodium minimum (1)" }
    }

    companion object {
        /** RECOMMENDED DEFAULT: moderate Argon2id, 256 MiB / 3 passes. */
        val DEFAULT = KdfParams(memLimitBytes = 268_435_456L, opsLimit = 3L)

        /** Stronger profile: ~1 GiB / 4 passes. */
        val SENSITIVE = KdfParams(memLimitBytes = 1_073_741_824L, opsLimit = 4L)

        /**
         * Low-end mobile floor: 64 MiB / 4 passes. Below the 256 MiB recommendation, but params
         * are per-user and upgradeable, so a device can re-derive at a higher cost later.
         */
        val MOBILE = KdfParams(memLimitBytes = 67_108_864L, opsLimit = 4L)

        /** TESTS ONLY — fast and deliberately NOT secure. Never ship this. */
        val TEST_FAST = KdfParams(memLimitBytes = 8_388_608L, opsLimit = 2L)
    }
}
