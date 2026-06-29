package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AccountKeyTest {

    @Test
    fun `wrap then unwrap recovers the account key`() {
        val kek = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        val accountKey = AccountKeyCrypto.generate()
        val expected = accountKey.copyBytes()

        val wrapped = AccountKeyCrypto.wrap(accountKey, kek)
        val unwrapped = AccountKeyCrypto.unwrap(wrapped, kek)
        assertArrayEquals(expected, unwrapped.copyBytes())
    }

    @Test
    fun `unwrap with the wrong KEK fails`() {
        val kek = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        val wrongKek = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        val accountKey = AccountKeyCrypto.generate()
        val wrapped = AccountKeyCrypto.wrap(accountKey, kek)
        assertThrows<AeadAuthException> { AccountKeyCrypto.unwrap(wrapped, wrongKek) }
    }

    @Test
    fun `recovery wrap then unwrap recovers the account key`() {
        val rwk = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        val accountKey = AccountKeyCrypto.generate()
        val expected = accountKey.copyBytes()

        val wrapped = AccountKeyCrypto.wrapForRecovery(accountKey, rwk)
        val unwrapped = AccountKeyCrypto.unwrapFromRecovery(wrapped, rwk)
        assertArrayEquals(expected, unwrapped.copyBytes())
    }

    @Test
    fun `password-wrapped blob cannot be opened with the recovery path (distinct associated data)`() {
        val key = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        val accountKey = AccountKeyCrypto.generate()
        val wrappedByPassword = AccountKeyCrypto.wrap(accountKey, key)
        // Same key bytes, but the recovery path uses a different AAD label -> must fail.
        assertThrows<AeadAuthException> { AccountKeyCrypto.unwrapFromRecovery(wrappedByPassword, key) }
    }
}
