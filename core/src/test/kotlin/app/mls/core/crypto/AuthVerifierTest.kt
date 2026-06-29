package app.mls.core.crypto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthVerifierTest {

    @Test
    fun `verify accepts the correct authKey and rejects a wrong one`() {
        val authKey = Sodium.randomBytes(Sodium.AEAD_KEY_BYTES)
        val verifier = AuthVerifier.create(authKey)
        assertTrue(AuthVerifier.verify(verifier, authKey))
        assertFalse(AuthVerifier.verify(verifier, Sodium.randomBytes(Sodium.AEAD_KEY_BYTES)))
    }

    @Test
    fun `verifier is a PHC argon2id string that does not contain the raw authKey`() {
        val authKey = Sodium.randomBytes(Sodium.AEAD_KEY_BYTES)
        val verifier = AuthVerifier.create(authKey)
        assertTrue(verifier.startsWith("\$argon2id\$"), "verifier should be an Argon2id PHC string")
        assertFalse(verifier.contains(B64.encode(authKey)), "verifier must not embed the authKey")
    }
}
