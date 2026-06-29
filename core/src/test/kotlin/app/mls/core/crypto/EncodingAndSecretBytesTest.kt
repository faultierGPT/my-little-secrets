package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EncodingAndSecretBytesTest {

    @Test
    fun `base32 round-trips random bytes`() {
        repeat(20) {
            val b = Sodium.randomBytes(32)
            assertArrayEquals(b, Base32.decode(Base32.encode(b)))
        }
    }

    @Test
    fun `base64 round-trips random bytes`() {
        val b = Sodium.randomBytes(40)
        assertArrayEquals(b, B64.decode(B64.encode(b)))
    }

    @Test
    fun `recovery code display parses back, tolerant of case and grouping`() {
        val rc = RecoveryCode.generate()
        assertArrayEquals(rc.rawKey, RecoveryCode.parse(rc.display()).rawKey)
        assertArrayEquals(rc.rawKey, RecoveryCode.parse(rc.display().lowercase().replace("-", "")).rawKey)
    }

    @Test
    fun `SecretBytes destroy wipes and blocks further access`() {
        val s = SecretBytes.wrap(byteArrayOf(1, 2, 3, 4))
        s.destroy()
        assertThrows<IllegalStateException> { s.bytes() }
    }

    @Test
    fun `SecretBytes double destroy is safe`() {
        val s = SecretBytes.wrap(byteArrayOf(1, 2, 3))
        s.destroy()
        s.destroy()
    }

    @Test
    fun `SecretBytes use destroys at end of block`() {
        val s = SecretBytes.wrap(byteArrayOf(9, 9, 9))
        s.use { assertEquals(3, it.size) }
        assertThrows<IllegalStateException> { s.bytes() }
    }
}
