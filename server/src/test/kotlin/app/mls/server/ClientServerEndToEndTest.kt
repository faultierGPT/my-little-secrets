package app.mls.server

import app.mls.core.api.KtorApiClient
import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.crypto.Sealed
import app.mls.core.model.LoginRequest
import app.mls.core.model.NotePayload
import app.mls.core.store.InMemoryNoteStore
import app.mls.core.sync.SyncEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The whole stack, for real: core crypto → core [KtorApiClient] → core [SyncEngine] → the Netty
 * server → H2, over a real loopback socket. Proves a note created on one device decrypts on a
 * second device and that the server only ever held ciphertext.
 */
class ClientServerEndToEndTest {

    @Test
    fun `two devices sync a note through the real server, which stores only ciphertext`() {
        val db = ServerTestSupport.freshH2Db()
        val deps = AppDeps.create(ServerTestSupport.testConfig(), db)
        val server = embeddedServer(Netty, port = 0) { module(deps) }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors() }.first().port
            val email = "dev@example.com"
            val password = "device-sync-pass"

            runBlocking {
                // ---- Device A: register, log in, unlock, create a note, sync ----
                val (regReq, _) = ServerTestSupport.registerRequestFor(email, password)
                KtorApiClient("http://localhost:$port").use { apiA ->
                    apiA.register(regReq)
                    val pA = apiA.loginParams(email)
                    apiA.login(LoginRequest(email, B64.encode(CryptoCore.deriveAuthKeyForLogin(password.toByteArray(), B64.decode(pA.salt), pA.kdfParams).copyBytes())))
                    val accKeyA = CryptoCore.unlockWithPassword(password.toByteArray(), B64.decode(pA.salt), pA.kdfParams, Sealed.fromBlob(apiA.getAccountKey().wrappedAccountKey))
                    val engineA = SyncEngine(apiA, InMemoryNoteStore(), accKeyA)
                    val id = engineA.save(NotePayload("Shared", "cross-device secret"))
                    engineA.sync()

                    // ---- Device B: independent login, same account, pulls + decrypts ----
                    KtorApiClient("http://localhost:$port").use { apiB ->
                        val pB = apiB.loginParams(email)
                        apiB.login(LoginRequest(email, B64.encode(CryptoCore.deriveAuthKeyForLogin(password.toByteArray(), B64.decode(pB.salt), pB.kdfParams).copyBytes())))
                        val accKeyB = CryptoCore.unlockWithPassword(password.toByteArray(), B64.decode(pB.salt), pB.kdfParams, Sealed.fromBlob(apiB.getAccountKey().wrappedAccountKey))
                        val engineB = SyncEngine(apiB, InMemoryNoteStore(), accKeyB)
                        engineB.sync()

                        val onB = engineB.list().single()
                        assertEquals(id, onB.id)
                        assertEquals("cross-device secret", onB.payload.body)
                    }

                    // ---- Server stored only ciphertext ----
                    db.tx { c ->
                        c.prepareStatement("SELECT ciphertext FROM notes WHERE id=?").use { ps ->
                            ps.setString(1, id)
                            ps.executeQuery().use { rs ->
                                assertTrue(rs.next())
                                assertFalse(rs.getString(1).contains("cross-device secret"))
                            }
                        }
                    }
                }
            }
        } finally {
            server.stop(100, 200)
            db.close()
        }
    }
}
