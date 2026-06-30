package app.mls.server

import app.mls.core.crypto.B64
import app.mls.core.crypto.CryptoCore
import app.mls.core.crypto.KdfParams
import app.mls.core.crypto.Sodium
import app.mls.core.model.LoginParamsResponse
import app.mls.core.model.LoginRequest
import app.mls.core.model.PasswordChangeRequest
import app.mls.core.model.RegisterRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthFlowTest {

    private suspend fun register(builder: io.ktor.server.testing.ApplicationTestBuilder, email: String, password: String) {
        val (req, _) = ServerTestSupport.registerRequestFor(email, password)
        val resp = builder.client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(RegisterRequest.serializer(), req))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `note endpoints require authentication`() = runServer { _, _ ->
        assertEquals(HttpStatusCode.Unauthorized, client.get("/notes?since=0").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/notes?since=0") { header(HttpHeaders.Authorization, "Bearer not-a-real-token") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, client.get("/account/key").status)
    }

    @Test
    fun `wrong password is rejected and repeated failures get rate-limited`() =
        runServer(ServerTestSupport.testConfig(loginMaxAttempts = 3, loginLockoutSeconds = 60)) { _, _ ->
            register(this, "bob@example.com", "right-password")

            // Three wrong logins -> 401, then the limiter locks the key -> 429.
            repeat(3) {
                val r = client.post("/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(appJson.encodeToString(LoginRequest.serializer(), LoginRequest("bob@example.com", B64.encode(Sodium.randomBytes(32)))))
                }
                assertEquals(HttpStatusCode.Unauthorized, r.status)
            }
            val locked = client.post("/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(LoginRequest.serializer(), LoginRequest("bob@example.com", B64.encode(Sodium.randomBytes(32)))))
            }
            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
        }

    @Test
    fun `password change re-wraps, invalidates old sessions, and rotates the credential`() = runServer { _, _ ->
        val email = "carol@example.com"
        val (req, material) = ServerTestSupport.registerRequestFor(email, "old-password")
        client.post("/auth/register") {
            contentType(ContentType.Application.Json); setBody(appJson.encodeToString(RegisterRequest.serializer(), req))
        }
        val oldToken = login("old-password", email)

        // Build the re-wrap locally (same account key, new wrapping + credential).
        val rewrap = CryptoCore.rewrapForNewPassword(material.accountKey, "new-password".toByteArray(), KdfParams.TEST_FAST)
        val change = client.post("/account/password") {
            header(HttpHeaders.Authorization, "Bearer $oldToken")
            contentType(ContentType.Application.Json)
            setBody(
                appJson.encodeToString(
                    PasswordChangeRequest.serializer(),
                    PasswordChangeRequest(
                        currentAuthKey = B64.encode(material.authKey.bytes()),
                        newSalt = B64.encode(rewrap.salt),
                        newKdfParams = rewrap.kdfParams,
                        newAuthKey = B64.encode(rewrap.authKey.bytes()),
                        newWrappedAccountKey = rewrap.wrappedAccountKey.toBlob(),
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.NoContent, change.status)

        // Old session is now invalid.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/account/key") { header(HttpHeaders.Authorization, "Bearer $oldToken") }.status,
        )

        // New password works...
        val newToken = login("new-password", email)
        assertEquals(
            HttpStatusCode.OK,
            client.get("/account/key") { header(HttpHeaders.Authorization, "Bearer $newToken") }.status,
        )

        // ...and the old password no longer authenticates.
        val params = appJson.decodeFromString(
            LoginParamsResponse.serializer(),
            client.post("/auth/login/params") {
                contentType(ContentType.Application.Json)
                setBody(appJson.encodeToString(app.mls.core.model.LoginParamsRequest.serializer(), app.mls.core.model.LoginParamsRequest(email)))
            }.bodyAsText(),
        )
        val oldAuthKey = CryptoCore.deriveAuthKeyForLogin("old-password".toByteArray(), B64.decode(params.salt), params.kdfParams)
        val rejected = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(appJson.encodeToString(LoginRequest.serializer(), LoginRequest(email, B64.encode(oldAuthKey.copyBytes()))))
        }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)
    }
}
