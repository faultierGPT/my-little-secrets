package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Exercises the REAL recommended profile (256 MiB Argon2id) at least once, so we know the
 * shipped default actually runs on this hardware — the rest of the suite uses a fast profile.
 */
class ProfileSanityTest {

    @Test
    fun `default 256 MiB profile derives a deterministic 32-byte master key`() {
        val pw = "daily-driver-password".toByteArray()
        val salt = Sodium.randomBytes(Sodium.PWHASH_SALT_BYTES)
        val a = KeyHierarchy.deriveMasterKey(pw, salt, KdfParams.DEFAULT).copyBytes()
        val b = KeyHierarchy.deriveMasterKey(pw, salt, KdfParams.DEFAULT).copyBytes()
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }
}
