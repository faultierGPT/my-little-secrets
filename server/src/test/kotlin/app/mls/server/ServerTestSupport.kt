package app.mls.server

import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.crypto.KdfParams
import app.mls.core.crypto.RegistrationMaterial
import app.mls.core.model.EncryptedBlob
import app.mls.core.model.RegisterRequest
import app.mls.server.db.Db
import java.util.UUID

/** A mutable clock for tests that need to advance time (token expiry, `since` cursors). */
class TestClock(var nowMillis: Long = 1_000_000L) {
    fun now(): Long = nowMillis
    fun advance(ms: Long) { nowMillis += ms }
}

object ServerTestSupport {

    fun testConfig(
        loginMaxAttempts: Int = 5,
        loginLockoutSeconds: Long = 60,
        tokenTtlSeconds: Long = 3600,
    ) = Config(
        port = 0,
        dbUrl = "jdbc:h2:mem:unused",
        dbUser = "sa",
        dbPassword = "",
        dbDriver = "org.h2.Driver",
        tokenTtlSeconds = tokenTtlSeconds,
        maxNoteCiphertextBytes = 1_048_576,
        hstsEnabled = true,
        corsHosts = emptyList(),
        loginMaxAttempts = loginMaxAttempts,
        loginLockoutSeconds = loginLockoutSeconds,
    )

    fun freshH2Db(): Db {
        val db = Db.create("jdbc:h2:mem:mls_${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "", "org.h2.Driver")
        db.initSchema()
        return db
    }

    /** Build a registration request from a password, exactly as a client would. */
    fun registerRequestFor(email: String, password: String, withRecovery: Boolean = true): Pair<RegisterRequest, RegistrationMaterial> {
        val material = CryptoCore.register(password.toByteArray(), KdfParams.TEST_FAST, withRecovery)
        val req = RegisterRequest(
            email = email,
            salt = B64.encode(material.salt),
            kdfParams = material.kdfParams,
            authKey = B64.encode(material.authKey.bytes()),
            wrappedAccountKey = material.wrappedAccountKey.toBlob(),
            wrappedAccountKeyRecovery = material.wrappedAccountKeyRecovery?.toBlob(),
            schemeVersion = 1,
        )
        return req to material
    }

    fun blob(ciphertext: ByteArray, nonce: ByteArray) = EncryptedBlob(B64.encode(ciphertext), B64.encode(nonce), 1)
}
