package app.mls.server

import app.mls.core.crypto.AeadAuthException
import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.crypto.NoteCrypto
import app.mls.core.crypto.Sealed
import app.mls.core.crypto.SecretBytes
import app.mls.core.crypto.Sodium
import app.mls.core.model.AccountKeyResponse
import app.mls.core.model.LoginParamsRequest
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.LoginResponse
import app.mls.core.model.NotePayload
import app.mls.core.model.NotesResponse
import app.mls.core.model.PutNoteRequest
import app.mls.core.model.RegisterRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The acceptance test the spec calls out: a full create→sync→read round-trip across the HTTP API,
 * followed by direct inspection of the database proving it holds only opaque ciphertext + metadata.
 */
class ServerZeroKnowledgeTest {

    @Test
    fun `round-trip works and the database stores only ciphertext`() = runServer { db, _ ->
        val password = "Sup3r-Secret-Master"
        val secretBody = "Wire transfer code 8842 — do not share"
        val (registerReq, _) = ServerTestSupport.registerRequestFor("Alice@Example.com ", password)

        // 1) Register
        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RegisterRequest.serializer(), registerReq))
        }
        assertEquals(HttpStatusCode.Created, reg.status)

        // 2) login/params (server normalises the email to trimmed lowercase)
        val paramsResp = client.post("/auth/login/params") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(LoginParamsRequest.serializer(), LoginParamsRequest("alice@example.com")))
        }
        assertEquals(HttpStatusCode.OK, paramsResp.status)
        val params = appJson.decodeFromString(LoginParamsResponse.serializer(), paramsResp.bodyAsText())

        // 3) derive authKey locally and log in
        val authKey = CryptoCore.deriveAuthKeyForLogin(password.toByteArray(), B64.decode(params.salt), params.kdfParams)
        val loginResp = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(LoginRequest.serializer(), LoginRequest("alice@example.com", B64.encode(authKey.copyBytes()))))
        }
        assertEquals(HttpStatusCode.OK, loginResp.status)
        val token = appJson.decodeFromString(LoginResponse.serializer(), loginResp.bodyAsText()).token

        // 4) fetch the wrapped account key and unlock it LOCALLY (the server never sees the account key)
        val keyResp = client.get("/account/key") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, keyResp.status)
        val accountKeyResp = appJson.decodeFromString(AccountKeyResponse.serializer(), keyResp.bodyAsText())
        val accountKey = CryptoCore.unlockWithPassword(
            password.toByteArray(), B64.decode(params.salt), params.kdfParams, Sealed.fromBlob(accountKeyResp.wrappedAccountKey),
        )

        // 5) encrypt a note locally and upload the ciphertext
        val noteId = "note-abc"
        val sealed = NoteCrypto.encrypt(accountKey, noteId, NotePayload("Banking", secretBody, listOf("money")))
        val putResp = client.put("/notes/$noteId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(PutNoteRequest.serializer(), PutNoteRequest(B64.encode(sealed.ciphertext), B64.encode(sealed.nonce), 1, 0)))
        }
        assertEquals(HttpStatusCode.OK, putResp.status)

        // 6) pull and decrypt
        val notesResp = client.get("/notes?since=0") { header(HttpHeaders.Authorization, "Bearer $token") }
        val notes = appJson.decodeFromString(NotesResponse.serializer(), notesResp.bodyAsText())
        assertEquals(1, notes.notes.size)
        val fetched = notes.notes.first()
        val decrypted = NoteCrypto.decrypt(
            accountKey, noteId, Sealed(B64.decode(fetched.ciphertext), B64.decode(fetched.nonce), fetched.schemeVersion),
        )
        assertEquals(secretBody, decrypted.body)

        // ---- ZERO-KNOWLEDGE: inspect the raw database rows ----
        db.tx { c ->
            c.prepareStatement("SELECT ciphertext FROM notes WHERE id=?").use { ps ->
                ps.setString(1, noteId)
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val storedCt = rs.getString(1)
                    assertFalse(storedCt.contains(B64.encode(secretBody.toByteArray())), "plaintext leaked (base64)")
                    assertFalse(String(B64.decode(storedCt), Charsets.ISO_8859_1).contains(secretBody), "plaintext leaked (raw)")
                }
            }
            c.prepareStatement("SELECT auth_verifier, wrapped_account_key FROM users WHERE email=?").use { ps ->
                ps.setString(1, "alice@example.com")
                ps.executeQuery().use { rs ->
                    assertTrue(rs.next())
                    val verifier = rs.getString(1)
                    val wrapped = rs.getString(2)
                    assertTrue(verifier.startsWith("\$argon2id\$"), "credential not stored as Argon2id verifier")
                    assertFalse(verifier.contains(password), "password leaked into verifier")
                    assertFalse(wrapped.contains(password), "password leaked into wrapped key")
                }
            }
        }

        // 7) The stored ciphertext is undecryptable with any key the server could hold.
        val attackerKey = SecretBytes.wrap(Sodium.randomBytes(Sodium.AEAD_KEY_BYTES))
        assertThrows<AeadAuthException> { NoteCrypto.decrypt(attackerKey, noteId, sealed) }
    }

    @Test
    fun `delete tombstones the note and clears its ciphertext`() = runServer { db, _ ->
        val password = "pw-for-delete"
        val (registerReq, material) = ServerTestSupport.registerRequestFor("del@example.com", password)
        client.post("/auth/register") {
            contentType(ContentType.Application.Json); setBody(appJson.encodeToString(RegisterRequest.serializer(), registerReq))
        }
        val token = login(password, "del@example.com")

        val noteId = "to-delete"
        val sealed = NoteCrypto.encrypt(material.accountKey, noteId, NotePayload("x", "y"))
        client.put("/notes/$noteId") {
            header(HttpHeaders.Authorization, "Bearer $token"); contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(PutNoteRequest.serializer(), PutNoteRequest(B64.encode(sealed.ciphertext), B64.encode(sealed.nonce))))
        }

        val del = client.delete("/notes/$noteId") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, del.status)

        // Tombstone appears in the sync feed and its ciphertext is gone from the DB.
        val notes = appJson.decodeFromString(
            NotesResponse.serializer(),
            client.get("/notes?since=0") { header(HttpHeaders.Authorization, "Bearer $token") }.bodyAsText(),
        )
        val tomb = notes.notes.single { it.id == noteId }
        assertTrue(tomb.deleted)
        db.tx { c ->
            c.prepareStatement("SELECT ciphertext FROM notes WHERE id=?").use { ps ->
                ps.setString(1, noteId)
                ps.executeQuery().use { rs -> assertTrue(rs.next()); assertEquals("", rs.getString(1)) }
            }
        }
    }
}
