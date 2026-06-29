package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class KdfTest {
    private val params = KdfParams.TEST_FAST

    @Test
    fun `master key derivation is deterministic for same inputs`() {
        val pw = "correct horse battery staple".toByteArray()
        val salt = ByteArray(Sodium.PWHASH_SALT_BYTES) { it.toByte() }
        val a = KeyHierarchy.deriveMasterKey(pw, salt, params).copyBytes()
        val b = KeyHierarchy.deriveMasterKey(pw, salt, params).copyBytes()
        assertEquals(32, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun `different salt yields a different master key`() {
        val pw = "same-password".toByteArray()
        val s1 = ByteArray(Sodium.PWHASH_SALT_BYTES) { 1 }
        val s2 = ByteArray(Sodium.PWHASH_SALT_BYTES) { 2 }
        val a = KeyHierarchy.deriveMasterKey(pw, s1, params).copyBytes()
        val b = KeyHierarchy.deriveMasterKey(pw, s2, params).copyBytes()
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `different password yields a different master key`() {
        val salt = ByteArray(Sodium.PWHASH_SALT_BYTES) { 7 }
        val a = KeyHierarchy.deriveMasterKey("alpha".toByteArray(), salt, params).copyBytes()
        val b = KeyHierarchy.deriveMasterKey("bravo".toByteArray(), salt, params).copyBytes()
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `authKey and keyEncryptionKey are independent (domain separation)`() {
        val salt = ByteArray(Sodium.PWHASH_SALT_BYTES) { 3 }
        KeyHierarchy.deriveMasterKey("pw".toByteArray(), salt, params).use { master ->
            val auth = KeyHierarchy.deriveAuthKey(master).copyBytes()
            val kek = KeyHierarchy.deriveKek(master).copyBytes()
            assertEquals(32, auth.size)
            assertEquals(32, kek.size)
            assertFalse(auth.contentEquals(kek))
        }
    }

    @Test
    fun `subkeys are deterministic across separate derivations`() {
        val salt = ByteArray(Sodium.PWHASH_SALT_BYTES) { 5 }
        val auth1 = KeyHierarchy.deriveMasterKey("pw".toByteArray(), salt, params)
            .use { KeyHierarchy.deriveAuthKey(it).copyBytes() }
        val auth2 = KeyHierarchy.deriveMasterKey("pw".toByteArray(), salt, params)
            .use { KeyHierarchy.deriveAuthKey(it).copyBytes() }
        assertArrayEquals(auth1, auth2)
    }
}
