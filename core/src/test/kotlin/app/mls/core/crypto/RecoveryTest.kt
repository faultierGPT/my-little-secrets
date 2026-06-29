package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RecoveryTest {
    private val params = KdfParams.TEST_FAST

    @Test
    fun `recovery code unlocks the same account key`() {
        val material = CryptoCore.register("pw".toByteArray(), params, withRecovery = true)
        assertNotNull(material.recoveryCode)
        assertNotNull(material.wrappedAccountKeyRecovery)

        // Simulate the user writing the code down and typing it back later.
        val recoveryCode = material.recoveryCode!!
        val parsed = RecoveryCode.parse(recoveryCode.display())
        assertArrayEquals(recoveryCode.rawKey, parsed.rawKey)

        val unlocked = CryptoCore.unlockWithRecovery(parsed, material.wrappedAccountKeyRecovery!!)
        assertArrayEquals(material.accountKey.copyBytes(), unlocked.copyBytes())
    }

    @Test
    fun `a wrong recovery code fails to unlock`() {
        val material = CryptoCore.register("pw".toByteArray(), params, withRecovery = true)
        val wrong = RecoveryCode.generate()
        assertThrows<AeadAuthException> {
            CryptoCore.unlockWithRecovery(wrong, material.wrappedAccountKeyRecovery!!)
        }
    }

    @Test
    fun `registering without recovery produces no recovery material`() {
        val material = CryptoCore.register("pw".toByteArray(), params, withRecovery = false)
        assertNull(material.recoveryCode)
        assertNull(material.wrappedAccountKeyRecovery)
    }
}
